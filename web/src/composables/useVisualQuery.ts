/**
 * useVisualQuery.ts
 *
 * 职责：可视化查询状态机：维护来源、输出字段、过滤、聚合、排序和 SQL 渲染，并合并探索向导结果。
 * 边界：所有派生 SQL 必须与构建器字段/来源同步；向导合并需保留已有别名、关联键和处理链，不覆盖用户配置。
 */

import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { queryWorkflowsApi, workspaceApi } from '@/api'
import type {
  ProgColumn,
  QueryPipelineStep,
  QueryWorkflowConfig,
  QueryWorkflowFieldRule,
  QueryWorkflowOrder,
  QueryWorkflowWhere
} from '@/types'
import {
  SQL_IDENT_RE,
  chTypeToKind,
  makeAlias,
  opShortName,
  stepArgsFilled,
  type ValueKind
} from '@/constants/queryBuilderMeta'

/** 表的一列(名 + 类型 + 推导出的值类型) */
export interface BuilderColumn {
  name: string
  type: string
  kind: ValueKind
  /** 列注释(ClickHouse DESCRIBE TABLE 的 comment 字段,可能为空) */
  comment?: string
}

/** 一张源表(主表或关联表) */
export interface BuilderSource {
  table: string
  columns: BuilderColumn[]
  /**
   * 关联键列(主表与关联表都用它对齐)。
   *
   * ⚠️ 后端多表 JOIN 的默认 ON 是「主表 mdn_norm = 关联表 mdn_norm」——
   *    两张表各自的「哪一列当关联键」由 mdnRules 决定(归一化后统一叫 mdn_norm)。
   *    所以多表时每张表都必须选一个关联键列,否则后端直接拒绝。
   */
  joinCol: string
  /** 关联键的处理链(可选,如 upper / trim),归一化后参与等值关联 */
  joinSteps: QueryPipelineStep[]
  loading: boolean
  /**
   * 列清单读取失败的原因(原本只有"可选 0 列",用户无从下手)。
   *
   * ⚠️ 不只在向导里要可见:构建器「② 输出字段」的分组勾选区、
   *    「① 关联」的关联键下拉都靠它决定“是没数据还是加载失败”。
   */
  colErr?: string
}

/**
 * 可视化查询构建器的状态与逻辑。
 *
 * 内部状态直接映射到后端 `QueryWorkflowConfig`:
 *   - `sources[]`  → config.sources[] + config.mdnRules[] + config.join
 *   - `fields[]`   → config.fields[]      (SourceIdx / SourceCol / Alias / Steps / Prog / Agg)
 *   - `where[]`    → config.where[]
 *   - `orderBy[]`  → config.orderBy[]
 *   - `groupBy`    → config.groupBy[]      (由粒度 + 聚合字段自动推导)
 *
 * SQL 生成走后端纯函数(`POST /query-workflows/preview { run:false }`),
 * 保证「预览的 SQL」与「运行的 SQL」完全一致。
 */
export function useVisualQuery(connId: Ref<number>, database: Ref<string>) {
  // ─────────────── 源表(第 0 张是主表,其余是 JOIN 进来的) ───────────────
  const sources = ref<BuilderSource[]>([])
  /** 当前在左侧树里展开/预览的源表下标 */
  const activeSourceIdx = ref(0)

  /** 兼容旧用法:主表名 */
  const table = computed(() => sources.value[0]?.table ?? '')
  /** 兼容旧用法:当前活动源表的列 */
  const tableColumns = computed(() => sources.value[activeSourceIdx.value]?.columns ?? [])
  const loadingSchema = computed(() => sources.value.some((s) => s.loading))
  /** 是否多表(多表时才显示「来源表 / 关联键」这些列) */
  const multiTable = computed(() => sources.value.length > 1)

  // ─────────────── 构建状态 ───────────────
  const fields = ref<QueryWorkflowFieldRule[]>([])
  const where = ref<QueryWorkflowWhere[]>([])
  /**
   * 聚合后过滤(HAVING)。
   *
   * ⚠️ 形状与 where 完全相同(后端复用同一套操作符白名单 / 值转义),
   *    区别只在作用位置:WHERE 在 GROUP BY 之前,HAVING 在之后。
   *    所以只有「结果粒度 = 汇总统计」且至少有一个聚合字段时才有意义。
   *    典型用法:筛 count() > 100 的城市。
   */
  const having = ref<QueryWorkflowWhere[]>([])
  const orderBy = ref<QueryWorkflowOrder[]>([])
  const limit = ref(1000)
  /**
   * 去重键:按这些输出列各保留一行(LIMIT 1 BY),其它列原样带出来。
   * 只在「保持明细」粒度下有意义 —— 汇总统计已经在用 GROUP BY 折叠了。
   */
  const distinctKeys = ref<string[]>([])
  /** 粒度:auto = 有聚合时自动按非聚合字段分组;detail = 强制明细(不分组) */
  const granularity = ref<'auto' | 'detail'>('auto')

  // ─────────────── 生成的 SQL ───────────────
  const sql = ref('')
  const sqlError = ref('')
  const generating = ref(false)
  /** 配置改过但结果表还是上一次运行的 → 提示用户重新运行 */
  const resultStale = ref(false)
  /**
   * 程序算子后处理计划(后端 /query-workflows/preview 生成)。
   * 运行 / 分页 / 导出都要带上它,否则 sm3/3des 列不会被计算。
   */
  const progCols = ref<ProgColumn[]>([])

  // ─────────────── 派生:粒度 / 分组 ───────────────
  const hasAgg = computed(() => fields.value.some((f) => !!f.agg))

  /**
   * HAVING 是否可用。
   * ⚠️ 两个条件都要看:粒度选「保持明细」时 groupBy 为空,HAVING 会挂在一条明细查询上,
   *    ClickHouse 直接报 "not under aggregate function"。
   */
  const havingUsable = computed(() => granularity.value === 'auto' && hasAgg.value)

  /** 汇总模式下作为「分组依据」的字段(非聚合字段) */
  const detailFields = computed(() => fields.value.filter((f) => !f.agg))

  /** 实际下发的 groupBy */
  const groupBy = computed<string[]>(() => {
    if (granularity.value === 'detail') return []
    if (!hasAgg.value) return []
    return detailFields.value.map((f) => f.alias)
  })

  const groupByLabels = computed(() => groupBy.value)

  /**
   * 一条过滤/聚合后过滤条件是否【已填完】——与后端 whereCondFilled 同构。
   *
   * ⚠️ 未填完的条件要【忽略】而不是报错:刚点「添加条件」时 value 必然是空的,
   *    那时候下发会拿到后端 400(数值列)或者生成 `col = ''`(字符串列,静默错筛)。
   *
   * ⚠️ 「填了一半」不算没填:BETWEEN 只给一边属于填错,交给后端报错,不要静默丢弃下界。
   */
  function isCondFilled(w: QueryWorkflowWhere): boolean {
    if (!w.column) return false
    if (w.op === 'distinct') return true
    if ((w.preset ?? '').trim()) {
      // ⚠️ 时间预设不等于「一定填好了」:custom 要手填起止两个值。
      //    两个都空 = 还没填(跳过 + 提示);只填一个算「填错了」,交给后端报错。
      if ((w.preset ?? '').trim() === 'custom') {
        return !!(w.value ?? '').trim() || !!(w.value2 ?? '').trim()
      }
      return true // 其它预设自己算区间
    }
    const op = (w.op ?? '').toLowerCase().trim()
    if (op === 'is_null' || op === 'is_not_null') return true
    if (op === 'between') return !!(w.value ?? '').trim() || !!(w.value2 ?? '').trim()
    return !!(w.value ?? '').trim()
  }

  // ─────────────── 派生:列信息 ───────────────
  /** 每张源表的 列名 → 列 映射(同名同列在不同表里类型可能不同,必须按表查) */
  const columnMaps = computed(() => {
    return sources.value.map((s) => {
      const m = new Map<string, BuilderColumn>()
      for (const c of s.columns) m.set(c.name, c)
      return m
    })
  })

  /**
   * 取某列的值类型。
   * ⚠️ 必须带 sourceIdx —— 两张表都有 `mdn` 时,类型可能一个 String 一个 UInt64,
   *    只按列名查会取错表,进而选错过滤操作符与可用算子。
   */
  function kindOf(col: string, sourceIdx = 0): ValueKind {
    return columnMaps.value[sourceIdx]?.get(col)?.kind ?? 'string'
  }

  // ─────────────── 派生:输出字段别名集合(用于自动命名去重) ───────────────
  function takenAliases(exceptIdx = -1): Set<string> {
    const s = new Set<string>()
    fields.value.forEach((f, i) => {
      if (i !== exceptIdx && f.alias) s.add(f.alias)
    })
    return s
  }

  // ─────────────── 派生:警告 ───────────────
  const warnings = computed<string[]>(() => {
    const out: string[] = []
    if (!table.value) out.push('请先选择一张表')
    if (table.value && fields.value.length === 0) out.push('请至少添加一个输出字段')

    // ── 多表 JOIN 的前置条件(后端会硬拒绝,这里是提前提示) ──
    if (multiTable.value) {
      if (sources.value.length > 1 && !joinType.value) out.push('请选择 JOIN 类型')
      const missing = sources.value.map((s, i) => (s.joinCol ? 0 : i + 1)).filter((n) => n > 0)
      if (missing.length) {
        out.push(`多表关联时每张源表都要指定「关联键列」,第 ${missing.join('、')} 张还没选`)
      }
      const badJoin = sources.value.findIndex(
        (s) => s.joinCol && s.columns.length > 0 && !s.columns.some((c) => c.name === s.joinCol)
      )
      if (badJoin >= 0) {
        out.push(`第 ${badJoin + 1} 张源表的关联键列「${sources.value[badJoin].joinCol}」不在该表里`)
      }
      // 自定义 ON 由后端校验「表别名.列名」,这里只提示两侧要成对填
      if ((joinOnLeft.value && !joinOnRight.value) || (!joinOnLeft.value && joinOnRight.value)) {
        out.push('自定义 ON 条件的两侧要一起填(或都留空 = 按关联键关联)')
      }
      // CROSS JOIN 不能带 ON —— 后端会直接报错,先在前端拦住
      if ((joinType.value || '').toUpperCase() === 'CROSS') {
        const hasOn = !!joinOnLeft.value.trim() || !!joinOnRight.value.trim() || Object.keys(joinOns.value).length > 0
        if (hasOn) out.push('CROSS JOIN 不支持 ON 条件(它是笛卡尔积)—— 请清空 ON,或改回 LEFT/INNER')
      }
    }
    // 字段必须指向存在的源表
    const badSrc = fields.value
      .map((f, i) => (f.sourceIdx < 0 || f.sourceIdx >= sources.value.length ? i + 1 : 0))
      .filter((n) => n > 0)
    if (badSrc.length) out.push(`第 ${badSrc.join('、')} 个输出字段的来源表已不存在(请重选或删除)`)
    const noCol = fields.value.map((f, i) => (f.sourceCol ? 0 : i + 1)).filter((n) => n > 0)
    if (noCol.length) out.push(`第 ${noCol.join('、')} 个输出字段还没选列`)
    // ⚠️ 只有「既有聚合字段、又有非聚合字段」时才有冲突 ——
    //    全部字段都带聚合时 detail 与 auto 生成的 SQL 完全等价(都是 SELECT count() …),
    //    这里报错只会把本来能跑的配置卡死(实测:运行按钮直接置灰)。
    //    与 loadConfig 里推导 granularity 的判定保持同一口径。
    if (granularity.value === 'detail' && hasAgg.value && detailFields.value.length > 0) {
      out.push('当前为「保持明细」,但有字段设了聚合 —— 聚合与明细不能混用(请改回「汇总统计」或去掉聚合)')
    }
    // ⚠️ 别名必须非空 —— 后端 BuildQueryPlan 要求 Alias 与 SourceCol 都非空,
    //    空别名以前不进 warnings,canGenerate 仍为 true,请求发出去才报错。
    if (fields.value.some((f) => !f.alias)) {
      out.push('存在空的输出字段别名(请填写或移除该字段)')
    }
    const bad = fields.value.filter((f) => f.alias && !SQL_IDENT_RE.test(f.alias))
    for (const f of bad) out.push(`别名 ${f.alias} 非法(只能用字母/数字/下划线,且不能以数字开头)`)
    // ⚠️ mdn_norm 是后端【保留别名】:它会被当成「直接引用上游归一化列」,
    //    该字段的 sourceCol / 处理链全被忽略,值被静默换成关联键列。
    //    前端必须拦住,否则用户看到的是「字段没变、值变了」。
    if (fields.value.some((f) => f.alias === 'mdn_norm')) {
      out.push('mdn_norm 是保留别名(代表各表的关联键归一化列),不能再作为输出字段别名 —— 请改名')
    }
    const dup = fields.value.map((f) => f.alias).filter((a, i, arr) => a && arr.indexOf(a) !== i)
    if (dup.length) out.push(`别名重复:${[...new Set(dup)].join(', ')}`)
    // 过滤/排序引用了已不存在的字段(改别名或删字段后可能残留)
    const alive = new Set(fields.value.map((f) => f.alias))
    const ghostWhere = where.value.filter((w) => w.column && !alive.has(w.column)).map((w) => w.column)
    const ghostHaving = having.value.filter((h) => h.column && !alive.has(h.column)).map((h) => h.column)
    const ghostOrder = orderBy.value.filter((o) => o.column && !alive.has(o.column)).map((o) => o.column)
    const ghost = [...new Set([...ghostWhere, ...ghostHaving, ...ghostOrder])]
    if (ghost.length) out.push(`过滤/聚合后过滤/排序引用了不存在的字段:${ghost.join(', ')}(请重新选择)`)
    // 程序算子列不能用于过滤/分组/排序(ClickHouse 看不到它的值)
    const badProg = [
      ...new Set([
        ...where.value.filter((w) => progAliases.value.has(w.column)).map((w) => w.column),
        ...having.value.filter((h) => progAliases.value.has(h.column)).map((h) => h.column),
        ...orderBy.value.filter((o) => progAliases.value.has(o.column)).map((o) => o.column),
        ...groupBy.value.filter((g) => progAliases.value.has(g))
      ])
    ]
    if (badProg.length) {
      out.push(`字段 ${badProg.join(', ')} 由程序算子计算,不能用于过滤/聚合后过滤/分组/排序 —— 请移除相关条件`)
    }
    // HAVING 没有聚合时后端会直接报错;先在前端拦下来,把原因说清楚
    if (having.value.some((h) => h.column) && !havingUsable.value) {
      out.push('聚合后过滤(HAVING)需要「结果粒度 = 汇总统计」且至少有一个聚合字段')
    }
    // 处理链 / 程序算子的必填参数没填 → 后端 renderPipelineArg 会报「数字参数不能为空」。
    // ⚠️ 这种【不能】静默忽略:丢一个步骤就改变了字段语义(如 substring 没了→变回原值),
    //    必须拦住并说清楚是哪个字段的哪个算子。
    const badArgs: string[] = []
    for (const f of fields.value) {
      for (const st of [...(f.steps ?? []), ...(f.prog ?? [])]) {
        if (!stepArgsFilled(st)) badArgs.push(`${f.alias} 的 ${opShortName(st.fn)}`)
      }
    }
    if (badArgs.length) {
      out.push(`这些算子的必填参数还没填:${badArgs.join('、')}`)
    }
    // countAll 数的是行数,处理链/程序算子的值参与不进来 → 后端会拒绝。
    // 先在前端说清楚,免得用户点运行才看到 400。
    const badCountAll = fields.value
      .filter((f) => f.agg === 'countAll' && ((f.steps ?? []).length > 0 || (f.prog ?? []).length > 0))
      .map((f) => f.alias)
    if (badCountAll.length) {
      out.push(
        `字段 ${badCountAll.join('、')} 用了「计数(行数)」,它数的是行数,不能再叠加处理链/程序算子 —— 想统计处理后的非空值请改用「计数(非空)」`
      )
    }
    return out
  })

  const canGenerate = computed(() => !!table.value && fields.value.length > 0 && warnings.value.length === 0)

  /**
   * 非阻塞提示(不影响能不能执行)。
   *
   * ⚠️ 必须与 warnings 分开:未填完的过滤条件是被【忽略】而不是报错,
   *    放进 warnings 会把 canGenerate 卡死 —— 等于换个地方继续拦人。
   */
  const notices = computed<string[]>(() => {
    const out: string[] = []
    const blankCol = where.value.filter((w) => !w.column).length + having.value.filter((h) => !h.column).length
    if (blankCol > 0) out.push(`有 ${blankCol} 条条件还没选字段,已暂时忽略`)
    const blankVal =
      where.value.filter((w) => w.column && !isCondFilled(w)).length +
      having.value.filter((h) => h.column && !isCondFilled(h)).length
    if (blankVal > 0) out.push(`有 ${blankVal} 条条件还没填值,已暂时忽略(填好后自动生效)`)
    // CROSS 是笛卡尔积:不拦,但必须说清楚行数风险
    if (multiTable.value && (joinType.value || '').toUpperCase() === 'CROSS') {
      out.push('当前是 CROSS JOIN(笛卡尔积):结果行数 = 各表行数相乘,请先确认数据量')
    }
    return out
  })

  // ─────────────── 源表操作 ───────────────
  // schema 请求序号:快速点两张表时,先发的若后返回会污染列清单
  let schemaSeq = 0

  /** JOIN 类型(后端白名单:LEFT / INNER / RIGHT / FULL / CROSS) */
  const joinType = ref('LEFT')
  /** 全局 ON 的两侧(留空 = 用各自的关联键归一化列 mdn_norm 做等值关联) */
  const joinOnLeft = ref('')
  const joinOnRight = ref('')
  /**
   * 逐表 ON 覆盖:key = 源表下标(≥1)。
   *
   * 用途:3 张表以上且各表关联键不同时(如 b 用 mdn_norm、c 用 uid_norm),
   * 全局 ON 只能表达一种,必须逐表指定。
   * 值形如 `{ onLeft, onRight }`;两侧留空 = 该表回退到全局/默认。
   */
  const joinOns = ref<Record<number, { onLeft: string; onRight: string }>>({})

  function setJoinOn(idx: number, side: 'onLeft' | 'onRight', v: string) {
    const cur = joinOns.value[idx] ?? { onLeft: '', onRight: '' }
    joinOns.value = { ...joinOns.value, [idx]: { ...cur, [side]: v } }
    scheduleRefresh()
  }

  /**
   * 拉取第 idx 张源表的列清单。
   *
   * ⚠️ 必须按【索引】取对象(不能用调用方传来的裸对象):
   *    sources 是 ref 数组,写入裸对象会绕过 Vue 的响应式代理 ——
   *    数据确实变了,但不触发重渲染,界面会停在「可选 0 列」(向导写入后表现为 1/0)。
   */
  async function fetchColumnsAt(idx: number, my: number): Promise<void> {
    const source = sources.value[idx]
    if (!source) return
    if (!connId.value || !database.value || !source.table) return
    source.loading = true
    source.colErr = ''
    try {
      const res = await workspaceApi.schemaSilent(connId.value, database.value, source.table)
      if (my !== schemaSeq) return
      const idxName = res.columns.indexOf('name')
      const idxType = res.columns.indexOf('type')
      const idxCmt = res.columns.indexOf('comment')
      source.columns = res.rows.map((r) => {
        const n = String(r[idxName >= 0 ? idxName : 0] ?? '')
        const t = String(r[idxType >= 0 ? idxType : 1] ?? '')
        const cmt = idxCmt >= 0 && r[idxCmt] != null ? String(r[idxCmt]) : ''
        return { name: n, type: t, kind: chTypeToKind(t), comment: cmt }
      })
      // 读到 0 列不能当作正常结果:后续所有选列 UI 都会是空的
      if (!source.columns.length) source.colErr = '没读到列(表可能不存在或没权限)'
    } catch (e) {
      if (my !== schemaSeq) return
      source.columns = []
      source.colErr = e instanceof Error ? e.message : '列读取失败'
    } finally {
      if (my === schemaSeq) source.loading = false
    }
  }

  /** 重新拉某张表的列(失败后重试;界面上的「重试」按钮调它) */
  async function retryColumns(idx: number): Promise<void> {
    await fetchColumnsAt(idx, ++schemaSeq)
  }

  /** 重新拉全部源表的列 */
  async function retryAllColumns(): Promise<void> {
    const my = ++schemaSeq
    await Promise.all(sources.value.map((_, i) => fetchColumnsAt(i, my)))
  }

  /** 设为主表:清空一切,从这张表重新开始 */
  async function loadTable(name: string) {
    if (!name || !connId.value || !database.value) return
    // ⚠️ 点的就是当前这张【唯一的】源表 → 直接返回。
    //    以前会继续走到下面的清空逻辑:「点一下收起字段列表」或手滑重复点一下,
    //    就把 fields/where/having/orderBy 全清空(实测 3 个字段 + 1 条条件 → 0/0),
    //    而且没有任何确认弹窗。
    if (name === sources.value[0]?.table && sources.value.length === 1) return
    const my = ++schemaSeq
    // ⚠️ 清空必须【同步】做:放到 await 之后的话,加载期间用户添加的字段
    //    会被随后返回的响应清掉(表现为「刚加的字段莫名消失」)。
    const src: BuilderSource = {
      table: name,
      columns: [],
      joinCol: '',
      joinSteps: [],
      loading: true
    }
    sources.value = [src]
    activeSourceIdx.value = 0
    fields.value = []
    where.value = []
    having.value = []
    orderBy.value = []
    await fetchColumnsAt(0, my)
    // 单表也允许配关联键(便于以后再加表);默认猜 mdn 列
    autoPickJoinCol(src)
    // 清掉旧的 JOIN 配置
    joinType.value = 'LEFT'
    joinOnLeft.value = ''
    joinOnRight.value = ''
    joinOns.value = {}
    scheduleRefresh()
  }

  /** 试探性地猜一个关联键列(优先 mdn / mdn_norm / phone) */
  function autoPickJoinCol(src: BuilderSource) {
    if (src.joinCol) return
    const guess = src.columns.find((c) => /^(mdn|mdn_norm|phone|mobile|手机号)$/i.test(c.name))
    if (guess) src.joinCol = guess.name
  }

  /** 追加一张关联表(用于 JOIN) */
  async function addSource(name: string) {
    if (!name || !connId.value || !database.value) return -1
    if (sources.value.some((s) => s.table === name)) return -1 // 同一张表不加两次
    if (sources.value.length >= 6) return -1 // 与后端可读性对齐,上限 6 张
    const my = ++schemaSeq
    const src: BuilderSource = {
      table: name,
      columns: [],
      joinCol: '',
      joinSteps: [],
      loading: true
    }
    sources.value.push(src)
    const idx = sources.value.length - 1
    await fetchColumnsAt(idx, my)
    autoPickJoinCol(src)
    scheduleRefresh()
    return idx
  }

  /** 移除一张关联表(主表不可移除),并把字段/引用的下标重映射 */
  function removeSource(idx: number) {
    if (idx <= 0 || idx >= sources.value.length) return // 主表不能删
    sources.value.splice(idx, 1)
    // ⚠️ 字段的来源表必须跟着重排 —— 只过滤不做重映射会让字段指向错表
    //    (若新表恰好有同名列,会静默返回错表的值)。
    const next: typeof fields.value = []
    for (const f of fields.value) {
      if (f.sourceIdx === idx) continue // 该来源被删 → 字段一并丢弃
      if (f.sourceIdx > idx) f.sourceIdx--
      next.push(f)
    }
    fields.value = next
    // ⚠️ 逐表 ON 的下标也要跟着重排 + 丢掉被删表的那条。
    //    不重排的话会留下一个指向不存在源表的 key:后端直接报「引用了不存在的源表下标」,
    //    而「关联」页签只渲染现有的源表 → 用户既看不到那条脏 ON、也没地方删。
    const ons: typeof joinOns.value = {}
    for (const [k, v] of Object.entries(joinOns.value)) {
      const i = Number(k)
      if (i === idx) continue // 该表被删 → 它的 ON 一并丢弃
      ons[i > idx ? i - 1 : i] = v
    }
    joinOns.value = ons
    if (activeSourceIdx.value >= sources.value.length) activeSourceIdx.value = 0
    scheduleRefresh()
  }

  /**
   * 向导批量写入多表配置(ExploreWizard 的「生成查询」)。
   *
   * 与逐个 loadTable/addSource 不同:
   * - 一次调用内先清空旧 sources 再按顺序重建,避免中间态触发多次 refreshSQL;
   * - fields/where/having/orderBy 是否保留由 keepRules 决定 —— 换主表时语义变了,
   *   旧字段/条件指向的表可能已不存在,默认丢弃;表集未变(只改关联键)时保留。
   */
  async function applyWizard(
    picked: {
      name: string
      joinCol: string
      joinSteps?: QueryPipelineStep[]
    }[],
    opts?: {
      keepRules?: boolean
      joinType?: string
      fields?: QueryWorkflowFieldRule[]
      where?: QueryWorkflowWhere[]
      orderBy?: QueryWorkflowOrder[]
    }
  ) {
    if (!picked.length || !connId.value || !database.value) return
    const my = ++schemaSeq
    const keep = opts?.keepRules ?? false
    // 同步重建 sources(避免在途响应写回旧状态)
    const nextSources: BuilderSource[] = picked.map((p) => ({
      table: p.name,
      columns: [],
      joinCol: p.joinCol,
      // 向导配好的归一化处理链(如 hex(MD5(mdn)) 带回,没配则保留旧值)
      joinSteps: (p.joinSteps ?? []).map((st) => ({
        fn: st.fn,
        args: (st.args ?? []).map((a) => ({ ...a }))
      })),
      loading: true
    }))
    sources.value = nextSources
    activeSourceIdx.value = 0
    if (opts?.joinType) joinType.value = opts.joinType
    if (!keep) {
      fields.value = []
      where.value = []
      having.value = []
      orderBy.value = []
      joinOnLeft.value = ''
      joinOnRight.value = ''
      joinOns.value = {}
    }
    // 向导第 3 步勾的输出字段:未传时保留旧字段 —— 增量加表/改关联键不会丢已配输出
    if (opts?.fields) fields.value = opts.fields.map((f) => ({ ...f }))
    // 向导第 4 步配的过滤/排序(同表集时优先于保留旧规则)
    if (opts?.where) where.value = opts.where.map((w) => ({ ...w }))
    if (opts?.orderBy) orderBy.value = opts.orderBy.map((o) => ({ ...o }))
    await Promise.all(
      nextSources.map(async (_, i) => {
        await fetchColumnsAt(i, my)
        // 关联键为空时猜一个(单表场景用户可不选)
        const s = sources.value[i]
        if (s) autoPickJoinCol(s)
        return s
      })
    )
    scheduleRefresh()
  }

  /** 设置某张源表的关联键列 */
  function setJoinCol(idx: number, col: string) {
    const src = sources.value[idx]
    if (!src) return
    src.joinCol = col
    scheduleRefresh()
  }

  /** 勾/取消「只参与查询、不输出」标记 */
  function setFieldHidden(i: number, v: boolean) {
    const f = fields.value[i]
    if (!f) return
    f.hidden = v || undefined
    // 不输出 → 该列的排序/过滤仍然合法(后端会改写引用),无需清理
    scheduleRefresh()
  }

  /**
   * 设置某张源表关联键的处理链(归一化)。
   *
   * 用途:两张表的手机号格式不一致时(如 A 带空格、B 已是大写哈希),
   * 各自归一化后 mdn_norm 才能对上 —— 否则 JOIN 结果为空且没有任何提示。
   */
  function setJoinSteps(idx: number, steps: QueryPipelineStep[]) {
    const src = sources.value[idx]
    if (!src) return
    src.joinSteps = steps.map((st) => ({
      fn: st.fn,
      args: (st.args ?? []).map((a) => ({ ...a }))
    }))
    scheduleRefresh()
  }

  // ─────────────── 字段操作 ───────────────
  /**
   * 按表分组勾选模式:勾/取消某源表的某列(输出字段面板顶部用)。
   *
   * 勾:不存在则追加;取消:移除该 表+列 的第一个匹配字段(保留同名其他字段)。
   * 已存在的列重复勾选是幂等的 —— UI 的 checkbox 只需要知道当前勾了哪些。
   */
  function setFieldChecked(sourceIdx: number, colName: string, checked: boolean) {
    if (checked) {
      const exists = fields.value.some((f) => f.sourceIdx === sourceIdx && f.sourceCol === colName)
      if (!exists) {
        const src = sources.value[sourceIdx]
        const col = src?.columns.find((c) => c.name === colName)
        if (col) addField(col, undefined, sourceIdx)
      }
    } else {
      const i = fields.value.findIndex((f) => f.sourceIdx === sourceIdx && f.sourceCol === colName)
      if (i >= 0) removeField(i)
    }
  }

  /** 某源表的某列当前是否已勾选(分组勾选面板回显) */
  function isFieldChecked(sourceIdx: number, colName: string): boolean {
    return fields.value.some((f) => f.sourceIdx === sourceIdx && f.sourceCol === colName)
  }

  function addField(col: BuilderColumn, at?: number, sourceIdx = 0) {
    const alias = makeAlias(col.name, '', takenAliases())
    // ⚠️ 多表时同名列会撞别名 → makeAlias 的去重后缀已经处理(takenAliases 跨表收集)
    const f: QueryWorkflowFieldRule = {
      alias,
      sourceIdx,
      sourceCol: col.name,
      steps: [],
      prog: [],
      agg: ''
    }
    if (at === undefined || at < 0 || at > fields.value.length) fields.value.push(f)
    else fields.value.splice(at, 0, f)
    scheduleRefresh()
  }

  /**
   * 把一张源表的多个列一次性加为输出字段(已加过的跳过),返回实际新增数量。
   *
   * 用途:想看全表时不用一列一列点。
   *
   * ⚠️ 别名必须在【本次循环内部】累积到 taken 里 —— makeAlias 只查不写,
   *    若不自己 add,同一批里重名的列会拿到同一个别名(别名重复会直接卡住生成)。
   */
  function addAllFields(sourceIdx: number, cols: BuilderColumn[], at?: number): number {
    const taken = takenAliases()
    const have = new Set(fields.value.filter((f) => (f.sourceIdx ?? 0) === sourceIdx).map((f) => f.sourceCol))
    const fresh: QueryWorkflowFieldRule[] = []
    for (const col of cols) {
      if (!col.name || have.has(col.name)) continue
      const alias = makeAlias(col.name, '', taken)
      taken.add(alias)
      // ⚠️ have 也要在循环里累加:否则同一批里重复出现的列会被加两次
      //    (生成 city 与 city_2 两个重复投影)。makeAlias 只查不写,这个坑在那里已经踩过一次。
      have.add(col.name)
      fresh.push({
        alias,
        sourceIdx,
        sourceCol: col.name,
        steps: [],
        prog: [],
        agg: ''
      })
    }
    if (!fresh.length) return 0
    if (at === undefined || at < 0 || at > fields.value.length) fields.value.push(...fresh)
    else fields.value.splice(at, 0, ...fresh)
    scheduleRefresh()
    return fresh.length
  }

  /** 改某字段的来源表(多表时字段表里的下拉) */
  function setFieldSource(i: number, sourceIdx: number) {
    const f = fields.value[i]
    if (!f) return
    f.sourceIdx = sourceIdx
    // 换了表 → 原来的列名在新表里未必存在;有同名同名列就留着,否则清空让用户重选
    // (字段表在多表时会给「字段」列一个下拉,所以「清空」不是死状态)
    const exists = columnMaps.value[sourceIdx]?.has(f.sourceCol)
    if (!exists) f.sourceCol = ''
    scheduleRefresh()
  }

  /**
   * 换列(字段表里「字段」列的下拉)。
   * ⚠️ 不用 setFieldSource 那套「不存在就清空」逻辑 —— 这里是用户主动选的列,
   *    直接写入即可。
   */
  function setFieldCol(i: number, col: string) {
    const f = fields.value[i]
    if (!f) return
    f.sourceCol = col
    scheduleRefresh()
  }

  function removeField(i: number) {
    fields.value.splice(i, 1)
    // 清理引用了已删除字段的过滤/排序条件(否则会生成引用不存在列的非法 SQL)
    const alive = new Set(fields.value.map((f) => f.alias))
    where.value = where.value.filter((w) => alive.has(w.column))
    having.value = having.value.filter((h) => alive.has(h.column))
    orderBy.value = orderBy.value.filter((o) => alive.has(o.column))
    scheduleRefresh()
  }

  function moveField(from: number, to: number) {
    if (from === to || from < 0 || to < 0 || from >= fields.value.length || to >= fields.value.length) return
    const [item] = fields.value.splice(from, 1)
    fields.value.splice(to, 0, item)
    scheduleRefresh()
  }

  function setAgg(i: number, agg: string) {
    const f = fields.value[i]
    if (!f) return
    const oldAgg = f.agg ?? ''
    if (oldAgg === agg) return
    f.agg = agg
    // 别名随聚合变化重新生成 —— 但只在「别名仍是【上一个聚合】自动生成的样子」时才动它,
    // 否则会把用户手改的别名无端覆盖掉。
    // (makeAlias 传空 taken 时不会加 _2 去重后缀,正好是「自动形态」的基准)
    const prevAuto = makeAlias(f.sourceCol, oldAgg, new Set<string>())
    const stem = f.alias.replace(/_\d+$/, '')
    if (stem === prevAuto) {
      const old = f.alias
      f.alias = makeAlias(f.sourceCol, agg, takenAliases(i))
      syncAliasRefs(old, f.alias)
    }
    scheduleRefresh()
  }

  /** 整体替换某字段的处理链(带参) */
  function setSteps(i: number, steps: QueryPipelineStep[]) {
    const f = fields.value[i]
    if (!f) return
    f.steps = steps.map((st) => ({
      fn: st.fn,
      args: (st.args ?? []).map((a) => ({ ...a }))
    }))
    f.pipeline = [] // 清理旧的无参链,避免 Steps/Pipeline 并存产生歧义
    scheduleRefresh()
  }

  /** 整体替换某字段的程序算子链(由 el-to-ck 计算,不进 SQL) */
  function setProg(i: number, prog: QueryPipelineStep[]) {
    const f = fields.value[i]
    if (!f) return
    f.prog = prog.map((st) => ({
      fn: st.fn,
      args: (st.args ?? []).map((a) => ({ ...a }))
    }))
    scheduleRefresh()
  }

  /** 被程序算子处理的字段别名 —— 这些列不能用于过滤/分组/排序 */
  const progAliases = computed(() => new Set(fields.value.filter((f) => (f.prog ?? []).length > 0).map((f) => f.alias)))

  function setAlias(i: number, alias: string) {
    const f = fields.value[i]
    if (!f) return
    const old = f.alias
    f.alias = alias.trim()
    syncAliasRefs(old, f.alias)
    scheduleRefresh()
  }

  /**
   * 别名变化后同步所有引用它的地方(过滤条件 + 排序)。
   * ⚠️ 必须同时覆盖 where —— 只同步 orderBy 会导致过滤下拉停留在旧别名。
   */
  function syncAliasRefs(oldAlias: string, newAlias: string) {
    if (!oldAlias || oldAlias === newAlias) return
    for (const w of where.value) if (w.column === oldAlias) w.column = newAlias
    for (const h of having.value) if (h.column === oldAlias) h.column = newAlias
    for (const o of orderBy.value) if (o.column === oldAlias) o.column = newAlias
  }

  // ─────────────── 过滤 ───────────────
  function addWhere(col?: string) {
    // ⚠️ 跳过程序算子列 —— 它们的值由后端在结果返回后计算,查询引擎看不到,下推不了
    //    (后端会拒绝;以前自动选中它会让 canGenerate 直接变 false,表现是"莫名其妙不能跑了")
    const usable = fields.value.filter((f) => !progAliases.value.has(f.alias))
    const c = col || usable[0]?.alias || ''
    const kind = kindOf(fields.value.find((f) => f.alias === c)?.sourceCol ?? c)
    where.value.push({
      column: c,
      op: kind === 'date' ? 'is_not_null' : '=',
      value: '',
      valueKind: kind === 'number' ? 'number' : 'string'
    })
    scheduleRefresh()
  }

  function removeWhere(i: number) {
    where.value.splice(i, 1)
    scheduleRefresh()
  }

  function setWhereColumn(i: number, col: string) {
    const w = where.value[i]
    if (!w) return
    w.column = col
    const f = fields.value.find((x) => x.alias === col)
    const kind = kindOf(f?.sourceCol ?? col)
    w.valueKind = kind === 'number' ? 'number' : 'string'
    // 操作符可能不适用于新类型,回退到 =
    w.op = w.op && opValidFor(kind, w.op) ? w.op : '='
    w.preset = ''
    scheduleRefresh()
  }

  function opValidFor(kind: ValueKind, op: string): boolean {
    // 与 Constants 里的 FILTER_OPS_BY_KIND 保持一致
    const common = ['is_null', 'is_not_null', 'distinct']
    if (common.includes(op)) return true
    if (kind === 'number') return ['=', '!=', '>', '>=', '<', '<=', 'between'].includes(op)
    if (kind === 'string')
      // 比较类(> >= < <= between)对文本同样有效 —— 只是语义为字典序,
      // 与 constants 里的 string 操作符表保持一致。
      return ['=', '!=', 'like', 'not_like', '>', '>=', '<', '<=', 'between', 'in', 'not_in'].includes(op)
    if (kind === 'date') return ['=', '!=', '>=', '<'].includes(op)
    return ['=', '!='].includes(op)
  }

  // ─────────────── 排序 ───────────────
  function addOrderBy() {
    const first = fields.value.find(
      (f) => !progAliases.value.has(f.alias) && !orderBy.value.some((o) => o.column === f.alias)
    )
    if (!first) return
    orderBy.value.push({ column: first.alias, desc: true })
    scheduleRefresh()
  }

  function removeOrderBy(i: number) {
    orderBy.value.splice(i, 1)
    scheduleRefresh()
  }

  // ─────────────── 聚合后过滤(HAVING) ───────────────
  /**
   * 新增一条 HAVING 条件。
   *
   * ⚠️ 默认选中第一个【聚合列】—— HAVING 的典型用法就是筛聚合结果(cnt > 100)。
   *    若默认落在分组列上,用户很容易写出「本应放 WHERE」的条件:能跑,但先聚合再过滤,
   *    白白多算一遍。
   */
  function addHaving() {
    const first = fields.value.find((f) => !!f.agg && !progAliases.value.has(f.alias))
    having.value.push({
      column: first?.alias ?? '',
      op: '>',
      value: '',
      valueKind: 'number'
    })
    scheduleRefresh()
  }

  function removeHaving(i: number) {
    having.value.splice(i, 1)
    scheduleRefresh()
  }

  /**
   * 一键清掉全部聚合后过滤条件。
   *
   * 用途:聚合字段被取消后 HAVING 会进入「残留但无处可删」的死状态 ——
   * 条件还在、warnings 卡死执行,而卡片里没有任何东西能删掉它。
   */
  function clearHaving() {
    having.value = []
    scheduleRefresh()
  }

  function setHavingColumn(i: number, col: string) {
    const h = having.value[i]
    if (!h) return
    h.column = col
    const f = fields.value.find((x) => x.alias === col)
    const kind = kindOf(f?.sourceCol ?? col)
    h.valueKind = kind === 'number' ? 'number' : 'string'
    // 操作符可能不适用于新类型,回退到 > (数字) / = (其余)
    h.op = h.op && opValidFor(kind, h.op) ? h.op : kind === 'number' ? '>' : '='
    h.preset = ''
    scheduleRefresh()
  }

  // ─────────────── 组装 config ───────────────
  function toConfig(): QueryWorkflowConfig {
    // 每张源表的列清单 = 它被用到的列 + 关联键列(后端对 Columns 逐列做标识符校验)
    const sourcesCfg = sources.value.map((src, i) => {
      const used = new Set(fields.value.filter((f) => f.sourceIdx === i).map((f) => f.sourceCol))
      if (src.joinCol) used.add(src.joinCol)
      const cols = [...used].filter(Boolean)
      return {
        connectionId: connId.value,
        database: database.value,
        table: src.table,
        columns: cols
      }
    })
    // 多表 JOIN 时后端要求每张源表都配一条 mdn 归一化规则(它决定各自的关联键列)。
    // 单表可以不配 —— 但配了也无害,且保留它能让 SQL 与多表时保持一致。
    const mdnRules = sources.value
      .map((src, i) => ({ src, i }))
      .filter(({ src }) => !!src.joinCol)
      .map(({ src, i }) => ({
        sourceIdx: i,
        sourceCol: src.joinCol,
        alias: 'mdn_norm',
        steps: (src.joinSteps ?? []).map((st) => ({
          fn: st.fn,
          args: (st.args ?? []).map((a) => ({ kind: a.kind, value: a.value }))
        }))
      }))
    return {
      sources: sourcesCfg,
      mdnRules,
      fields: fields.value.map((f) => ({
        alias: f.alias,
        sourceIdx: f.sourceIdx ?? 0,
        sourceCol: f.sourceCol,
        steps: (f.steps ?? []).map((st) => ({
          fn: st.fn,
          args: (st.args ?? []).map((a) => ({ kind: a.kind, value: a.value }))
        })),
        prog: (f.prog ?? []).map((st) => ({
          fn: st.fn,
          args: (st.args ?? []).map((a) => ({ kind: a.kind, value: a.value }))
        })),
        agg: f.agg ?? '',
        // 只参与查询、不输出(后端据此跳过外层 SELECT)
        hidden: f.hidden || undefined
      })),
      join: {
        type: joinType.value || 'LEFT',
        onLeft: joinOnLeft.value.trim(),
        onRight: joinOnRight.value.trim(),
        ons: Object.entries(joinOns.value)
          .map(([k, v]) => ({
            sourceIdx: Number(k),
            onLeft: v.onLeft.trim(),
            onRight: v.onRight.trim()
          }))
          .filter((o) => o.onLeft || o.onRight) // 两侧都空的项不下发
      },
      where: where.value.filter(isCondFilled),
      having: having.value.filter(isCondFilled),
      orderBy: orderBy.value.filter((o) => o.column),
      limit: limit.value,
      // 汇总统计(有 groupBy)时不下去重键 —— 两者语义冲突,后端也会拒绝
      distinctKeys: groupBy.value.length ? [] : distinctKeys.value,
      groupBy: groupBy.value
    }
  }

  // ─────────────── SQL 生成(防抖 300ms) ───────────────
  let timer: ReturnType<typeof setTimeout> | null = null
  let seq = 0

  function scheduleRefresh() {
    resultStale.value = true
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      void refreshSQL()
    }, 300)
  }

  // ⚠️ 组件销毁时清掉未到期的防抖定时器 —— 否则离开编辑器后还会白发一次 preview 请求
  onScopeDispose(() => {
    if (timer) clearTimeout(timer)
    timer = null
  })

  /** 运行成功后调用:当前结果已对应当前配置 */
  function markFresh() {
    resultStale.value = false
  }

  async function refreshSQL() {
    if (!canGenerate.value) {
      // ⚠️ 早退也要推进 seq —— 否则在途的 preview 返回时 my === seq 仍成立,
      //    会把「上一份配置的 SQL」写回来(展示的 SQL 与当前配置不符)。
      seq++
      sql.value = ''
      sqlError.value = ''
      progCols.value = []
      generating.value = false
      return
    }
    const my = ++seq
    generating.value = true
    try {
      const res = await queryWorkflowsApi.preview({
        config: toConfig(),
        run: false
      })
      if (my !== seq) return
      sql.value = res.sql || ''
      progCols.value = res.progCols ?? []
      sqlError.value = ''
    } catch (e: any) {
      if (my !== seq) return
      sql.value = ''
      sqlError.value = e?.message || '生成 SQL 失败'
    } finally {
      if (my === seq) generating.value = false
    }
  }

  /** 立即生成(不防抖),用于「运行」前确保 SQL 最新 */
  async function refreshSQLNow(): Promise<string> {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    await refreshSQL()
    return sql.value
  }

  // ─────────────── 重置 / 载入 ───────────────
  function reset() {
    // 让在途的 schema / preview 请求作废(切换连接或库时,旧响应不能写回来)
    schemaSeq++
    seq++
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    sources.value = []
    activeSourceIdx.value = 0
    joinType.value = 'LEFT'
    joinOnLeft.value = ''
    joinOnRight.value = ''
    // ⚠️ 逐表 ON 必须一起清:只清全局 ON 的话,新查询里若恰好有第 2/3 张源表,
    //    旧的逐表 ON 会静默生效在那张表上(而 reset 的语义是「一切归零」)。
    joinOns.value = {}
    fields.value = []
    where.value = []
    having.value = []
    orderBy.value = []
    limit.value = 1000
    granularity.value = 'auto'
    sql.value = ''
    sqlError.value = ''
    progCols.value = []
    generating.value = false
    resultStale.value = false
  }

  /** 从后端 config 载入(用于打开已保存的查询) */
  async function loadConfig(cfg: QueryWorkflowConfig) {
    const srcs = cfg.sources ?? []
    if (!srcs.length) {
      reset()
      return
    }
    const my = ++schemaSeq
    // ⚠️ 多表配置必须把【每一张】源表都载回来,否则关联键下拉是空的、
    //    字段的来源表也没得选,保存直接被拦死。
    const ruleBySrc = new Map((cfg.mdnRules ?? []).map((r) => [r.sourceIdx, r]))
    sources.value = srcs.map((sc, i) => {
      const rule = ruleBySrc.get(i)
      return {
        table: sc.table,
        columns: [],
        joinCol: rule?.sourceCol ?? '',
        joinSteps: (rule?.steps ?? (rule?.pipeline ?? []).map((fn) => ({ fn, args: [] }))).map((st) => ({
          fn: st.fn,
          args: (st.args ?? []).map((a) => ({ ...a }))
        })),
        loading: true
      }
    })
    activeSourceIdx.value = 0
    await Promise.all(sources.value.map((_, i) => fetchColumnsAt(i, my)))
    // 没配过关联键的表,尝试猜一个(让用户少点一下)
    for (const src of sources.value) autoPickJoinCol(src)

    joinType.value = cfg.join?.type || 'LEFT'
    joinOnLeft.value = cfg.join?.onLeft ?? ''
    joinOnRight.value = cfg.join?.onRight ?? ''
    joinOns.value = {}
    for (const o of cfg.join?.ons ?? []) {
      joinOns.value[o.sourceIdx] = {
        onLeft: o.onLeft ?? '',
        onRight: o.onRight ?? ''
      }
    }

    fields.value = (cfg.fields ?? []).map((f) => ({
      alias: f.alias,
      // 老配置(单表)sourceIdx 恒为 0;多表时用真实值
      sourceIdx: f.sourceIdx ?? 0,
      sourceCol: f.sourceCol,
      // 兼容旧配置:无 steps 时把 pipeline([]string) 转成无参 steps
      steps: f.steps?.length
        ? f.steps.map((st) => ({
            fn: st.fn,
            args: (st.args ?? []).map((a) => ({ ...a }))
          }))
        : (f.pipeline ?? []).map((fn) => ({ fn, args: [] })),
      prog: (f.prog ?? []).map((st) => ({
        fn: st.fn,
        args: (st.args ?? []).map((a) => ({ ...a }))
      })),
      hidden: f.hidden || undefined,
      agg: f.agg ?? ''
    }))
    where.value = (cfg.where ?? []).map((w) => ({ ...w }))
    having.value = (cfg.having ?? []).map((h) => ({ ...h }))
    orderBy.value = (cfg.orderBy ?? []).map((o) => ({ ...o }))
    // ⚠️ 用 typeof 判断而不是 `||` —— 0 是 UI 明文支持的合法值(不限制),
    //    用 `||` 会把「不限制」悄悄变成 1000,重开后结果被静默截断。
    limit.value = typeof cfg.limit === 'number' && cfg.limit >= 0 ? cfg.limit : 1000
    distinctKeys.value = [...(cfg.distinctKeys ?? [])]
    const gb = cfg.groupBy ?? (cfg.groupByMdn ? ['mdn_norm'] : [])
    // ⚠️ 只有「既有聚合字段、又有非聚合字段」时才判定为「保持明细」。
    //    全部字段都带聚合(groupBy 天然为空,例如只有 countAll)时,auto 与 detail 生成的 SQL
    //    完全等价;判成 detail 只会凭空产生「聚合函数会被忽略」的警告,把 canGenerate 卡死
    //    —— 表现就是「打开已保存的聚合查询后运行按钮直接置灰、SQL 被清空」。
    granularity.value = gb.length === 0 && hasAgg.value && detailFields.value.length > 0 ? 'detail' : 'auto'
    await refreshSQLNow()
  }

  // 连接/库变化 → 重置(避免跨库残留)
  watch([connId, database], () => reset())

  return {
    // 状态
    sources,
    activeSourceIdx,
    multiTable,
    joinType,
    joinOnLeft,
    joinOnRight,
    table,
    tableColumns,
    loadingSchema,
    fields,
    where,
    having,
    havingUsable,
    orderBy,
    limit,
    granularity,
    distinctKeys,
    // SQL
    sql,
    sqlError,
    generating,
    resultStale,
    progCols,
    // 派生
    hasAgg,
    detailFields,
    groupBy,
    groupByLabels,
    warnings,
    canGenerate,
    notices,
    columnMaps,
    // 动作
    loadTable,
    applyWizard,
    retryColumns,
    retryAllColumns,
    addSource,
    removeSource,
    setJoinCol,
    setJoinSteps,
    joinOns,
    setJoinOn,
    addField,
    setFieldChecked,
    isFieldChecked,
    addAllFields,
    setFieldSource,
    setFieldCol,
    setFieldHidden,
    removeField,
    moveField,
    setAgg,
    setSteps,
    setProg,
    progAliases,
    setAlias,
    addWhere,
    removeWhere,
    setWhereColumn,
    addHaving,
    removeHaving,
    clearHaving,
    setHavingColumn,
    addOrderBy,
    removeOrderBy,
    kindOf,
    toConfig,
    refreshSQL,
    refreshSQLNow,
    markFresh,
    scheduleRefresh,
    reset,
    loadConfig
  }
}
