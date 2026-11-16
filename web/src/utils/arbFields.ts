/**
 * arbFields.ts
 *
 * 职责：仲裁字段工具：生成输出名、检查求和兼容性、自动映射、构建列、生成画像下钻条件并识别号码/数据包。
 * 边界：求和类型与输出名必须匹配后端仲裁规则；旧“人群包”标记只用于兼容，宽表草稿必须保留来源和筛选信息。
 */

/**
 * 工作流 · 对接层的纯逻辑。
 *
 * 抽出来是为了能单测:界面上的「按同名列自动挂载」和「保存时装配目标列」
 * 一旦写错,表现是生成的 SQL 少了列或者值取错了源 —— 都是不报错但结果不对那一类。
 */
import type { ArbColumn, ArbField, ArbMode, ArbRule, ArbSource, ArbWorkflowInput } from '@/types'

/** 目标列的取值配置 */
export interface ArbColCfg {
  mode: ArbMode
  rule: ArbRule
}

/** 默认:仲裁 + 非空 > 优先级 > 时间 */
export const ARB_DEFAULT_CFG: ArbColCfg = { mode: 'arbitrate', rule: 'pri_time' }

/** 目标列清单:按字段出现顺序去重(outName 为空时回退到源列名) */
export function arbOutNames(fields: ArbField[]): string[] {
  const seen: string[] = []
  for (const f of fields) {
    const n = (f.outName || '').trim() || f.col
    if (n && !seen.includes(n)) seen.push(n)
  }
  return seen
}

/** 与后端 ArbPlanBuilder 的 sum 类型范围一致；包括 ClickHouse 常见的两层包装。 */
export function arbSummableType(type?: string): boolean {
  let base = (type || '').trim()
  while (true) {
    const wrapped = /^(?:Nullable|LowCardinality)\((.*)\)$/.exec(base)
    if (!wrapped) break
    base = wrapped[1].trim()
  }
  return /^(?:U?Int(?:8|16|32|64)|Float(?:32|64)|Decimal\(\d+,\s*\d+\))$/.test(base)
}

/** 目标列有任意一个非数值来源时，整列都不能选择 sum。 */
export function arbSumTypeIssue(fields: ArbField[], outName: string): string | null {
  for (const field of fields) {
    if (((field.outName || '').trim() || field.col) !== outName) continue
    if (!arbSummableType(field.colType)) {
      return `目标列「${outName}」的来源字段「${field.col}」类型 ${field.colType || '未知'} 不能求和（只支持数值/Decimal）`
    }
  }
  return null
}

/**
 * 按同名列自动挂载。
 *
 * 规则:所有源里出现过的列名 = 目标列集合,每个源把名字命中该集合的列挂上
 * (输出列名 = 源列名)。于是 name / city 这类同名列自动汇到同一个目标列,
 * 而 operator.phone ↔ app.mdn 这种列名不同的仍然需要手工挂。
 *
 * 两条不能破的语义:
 *   1. 只【增加】,不删已有字段 —— 手挂的非同名字段和它们的处理链必须留着;
 *      否则点一下按钮就把用户配好的处理链清空了。
 *   2. 号码列不进目标列(它在聚合表里叫 mdn,后端也把 mdn 当保留名拦下来)。
 */
export function arbAutoMap(
  sources: { id: string; mdnCol: string }[],
  colsBySrc: Record<string, { name: string; type: string; comment?: string }[]>,
  existing: ArbField[] = []
): ArbField[] {
  const names = new Set<string>()
  for (const s of sources) {
    for (const c of colsBySrc[s.id] || []) {
      if (c.name === s.mdnCol) continue
      names.add(c.name)
    }
  }

  const byKey = new Map<string, ArbField>()
  for (const f of existing) {
    const id = sources.find((x) => x.id === f.sourceId)
    if (!id) continue // 源已经被删掉 → 它的字段一起丢
    if (f.col === id.mdnCol) continue // 号码列不当目标列
    byKey.set(`${f.sourceId}\u0000${f.col}`, f)
  }
  for (const s of sources) {
    for (const c of colsBySrc[s.id] || []) {
      if (c.name === s.mdnCol || !names.has(c.name)) continue
      const key = `${s.id}\u0000${c.name}`
      if (!byKey.has(key)) {
        const f: ArbField = { sourceId: s.id, col: c.name, outName: c.name, colType: c.type, steps: [] }
        // 源列注释当成这一列的默认业务说明(后端会把它写成表的 COMMENT)。
        // 空的不写:否则每个字段都多带一个空键,与「没填过说明」分不出来。
        if (c.comment) f.comment = c.comment
        byKey.set(key, f)
      }
    }
  }
  return [...byKey.values()]
}

/** 保存时装配目标列:取值方式只在仲裁时才带规则 */
export function arbBuildColumns(
  outNames: string[],
  cfg: Record<string, ArbColCfg | undefined>
): ArbColumn[] {
  return outNames.map((name) => {
    const c = cfg[name] || ARB_DEFAULT_CFG
    const col: ArbColumn = { name, mode: c.mode }
    if (c.mode === 'arbitrate') col.rule = c.rule
    return col
  })
}

/* ───────────────── 画像下钻:一个分布取值 → 圈选条件 ───────────────── */

export interface DrillCond {
  op: string
  value?: string
  lo?: string
  hi?: string
}

const pad = (n: number) => String(n).padStart(2, '0')

/**
 * 把「分布里的一个取值」翻译成圈选条件。
 *
 * 必须按分桶方式分情况 —— 都当等值处理会出错:
 *   raw            原值     → 等于;取值为 '(空)' 时是「为空」(后端把空串标成这个)
 *   number         数值区间 → 桶标签形如 "10-20"(左闭右开),所以是
 *                            「>= 10」+「< 20」两个条件;用 between 会把 20 重复算进相邻桶
 *   day/month/year 时间     → 区间:桶标签是周期起点,要补到该周期最后一秒
 *
 * 返回数组是因为数值桶要两个条件(区间是左闭右开,单个 between 表达不了)。
 */
export function arbDrillConds(column: string, value: string, bucket: string): DrillCond[] {
  if (value === '(空)') return [{ op: 'isnull' }]

  if (bucket === 'number') {
    const m = /^(-?[\d.]+)-(-?[\d.]+)$/.exec(value)
    if (m && !Number.isNaN(Number(m[1])) && !Number.isNaN(Number(m[2]))) {
      return [
        { op: 'gte', value: m[1] },
        { op: 'lt', value: m[2] },
      ]
    }
    return [{ op: 'eq', value }]
  }

  if (bucket === 'day' || bucket === 'month' || bucket === 'year') {
    const d = new Date(value.replace(' ', 'T'))
    if (Number.isNaN(d.getTime())) return [{ op: 'eq', value }]
    const y = d.getFullYear()
    const mo = d.getMonth()
    const day = d.getDate()
    let hi: string
    if (bucket === 'day') {
      hi = `${y}-${pad(mo + 1)}-${pad(day)} 23:59:59`
    } else if (bucket === 'month') {
      // 月末天数:下个月的第 0 天 = 本月最后一天
      const last = new Date(y, mo + 1, 0).getDate()
      hi = `${y}-${pad(mo + 1)}-${pad(last)} 23:59:59`
    } else {
      hi = `${y}-12-31 23:59:59`
    }
    return [{ op: 'between', lo: `${y}-${pad(mo + 1)}-${pad(day)} 00:00:00`, hi }]
  }

  return [{ op: 'eq', value }]
}

/** 下钻提示语(给用户看的一句话,说明刚才圈的是什么) */
export function arbDrillTip(column: string, value: string, bucket: string): string {
  if (value === '(空)') return `${column} 为空`
  if (bucket === 'number') return `${column} 在 ${value}`
  if (bucket === 'day') return `${column} 在 ${value.slice(0, 10)} 这一天`
  if (bucket === 'month') return `${column} 在 ${value.slice(0, 7)} 这个月`
  if (bucket === 'year') return `${column} 在 ${value.slice(0, 4)} 这一年`
  return `${column} = ${value}`
}

/* ───────────────── 数据包识别(认表注释) ───────────────── */

/**
 * 数据包的表注释前缀。
 *
 * ⚠️ 必须与后端 service.CrowdTableCommentPrefix 一致 —— 建表时由后端写进
 * ClickHouse 表注释,这里按它把数据包从一堆表里筛出来。不另建元数据表:
 * 表本身就是记录,库被删掉也不会留下孤儿数据。
 */
export const ARB_CROWD_MARK = '数据包'

/**
 * 老前缀。这个功能最初叫「人群包」,已经落库的注释里写的是老名字 ——
 * 读取时两种都认,改名才不会让老数据包凭空消失(否则只能靠名字 crowd_ 兜底,
 * 被改过名的老包就找不回来了)。
 */
export const ARB_CROWD_MARK_LEGACY = ['人群包']

export interface CrowdInfo {
  isCrowd: boolean
  /** 从哪张目标表圈的 */
  source: string
  /** 什么时候圈的 */
  createdAt: string
  /** 圈选条件 */
  cond: string
}

/**
 * 从「表名 + 表注释」认出数据包,并解析出注释里的三段信息。
 *
 * 注释格式:数据包｜来源 <源表>｜<时间>｜条件 <条件>
 *
 * 为什么名字前缀也认:注释是后来才加的,早先建的 crowd_* 没有注释,
 * 只用注释筛会漏掉它们。判据仍然以注释优先,名字只是兜底。
 */
export function arbParseCrowd(name: string, remark?: string): CrowdInfo {
  const r = (remark || '').trim()
  const isCrowd =
    r.startsWith(ARB_CROWD_MARK) ||
    ARB_CROWD_MARK_LEGACY.some((m) => r.startsWith(m)) ||
    name.startsWith('crowd_')
  if (!isCrowd) return { isCrowd: false, source: '', createdAt: '', cond: '' }
  const parts = r.split('｜').map((x) => x.trim())
  const source = (parts.find((x) => x.startsWith('来源 ')) || '').slice(3).trim()
  const condPart = parts.find((x) => x.startsWith('条件 '))
  // 时间单独找:条件里也可能出现日期,按「整段就是一个时间」才认
  const datePart = parts.find((x) => /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(x))
  return {
    isCrowd: true,
    source,
    createdAt: datePart || '',
    cond: condPart ? condPart.slice(3).trim() : '',
  }
}

/* ───────────────── 汇总宽表:草稿组装 ───────────────── */

/**
 * 从表结构里认出号码列。
 *
 * 优先看列注释里的「手机号」(同步任务配的业务名),其次看列名字面是 mdn。
 * 与工作流向导共用同一套规则 —— 提到这里是为了两边不会再各自演化。
 */
export function arbDetectMdn(cols: { name: string; comment?: string }[]): {
  col: string
  from: 'comment' | 'default' | ''
} {
  const byComment = cols.find((c) => (c.comment || '').includes('手机号'))
  if (byComment) return { col: byComment.name, from: 'comment' }
  const byName = cols.find((c) => c.name === 'mdn')
  if (byName) return { col: byName.name, from: 'default' }
  return { col: '', from: '' }
}

/** 宽表草稿的候选源:一张贴源表 + 它的列 */
export interface WideCandidate {
  database: string
  table: string
  cols: { name: string; type: string; comment?: string }[]
}

/** 同步框架给每张贴源表加的时间列(按优先级取第一个存在的) */
const WIDE_TIME_COLS = ['ods_insert_at', 'insert_at', 'ods_time', 'created_at']
/** 不并入宽表的技术列:它是"这行谁写的",合并成一张表之后没有意义 */
const WIDE_SKIP_COLS = ['ods_source_id']

/**
 * 组装「汇总宽表」草稿 —— 所有带号码列的贴源表并成一张大宽表。
 *
 * 草稿是**起点**不是答案:同名列会自动落到同一个目标列(这正是宽表要的 ——
 * 靠仲裁取最可信的那个值),但【取值方式一律先给仲裁】。计数/金额类到底该不该 sum
 * 是业务判断,猜错的代价是静默算错(值翻倍),所以留给人确认后再保存。
 *
 * 返回 null = 一张带号码列的源都没有(没什么可汇总)。
 */
export function arbWideDraft(connectionId: number, cands: WideCandidate[]): ArbWorkflowInput | null {
  const sources: ArbSource[] = []
  const fields: ArbField[] = []
  const outNames: string[] = []
  for (const c of cands) {
    const mdn = arbDetectMdn(c.cols)
    if (!mdn.col) continue // 没有号码列的表进不了宽表(不知道按谁对齐)
    const id = `s${sources.length}`
    sources.push({
      id,
      database: c.database,
      table: c.table,
      mdnCol: mdn.col,
      timeCol: WIDE_TIME_COLS.find((t) => c.cols.some((x) => x.name === t)) ?? '',
      pri: 0, // 默认同优先级 → 仲裁按「非空 + 时间」决定;谁更权威到向导里调
      enabled: true,
    })
    for (const col of c.cols) {
      if (col.name === mdn.col || WIDE_SKIP_COLS.includes(col.name)) continue
      fields.push({ sourceId: id, col: col.name, outName: col.name, colType: col.type, steps: [] })
      if (!outNames.includes(col.name)) outNames.push(col.name)
    }
  }
  if (!sources.length) return null
  return {
    name: '汇总宽表',
    description: `自动草稿:${sources.length} 个源、${outNames.length} 个字段。列名与取值方式请按口径确认后保存。`,
    enabled: false, // 草稿不自动开跑:确认前不建表、不摊平
    isWide: true,
    connectionId,
    targetDb: 'dwd_data',
    targetTable: 'all',
    sources,
    fields,
    columns: outNames.map((n) => ({ name: n, mode: 'arbitrate' as const, rule: 'pri_time' as const })),
  }
}
