/**
 * createTable.ts
 *
 * 职责：建表工具：校验字段网格、生成 ClickHouse DDL，并解析、拆分和高亮现有 DDL。
 * 边界：生成结果必须保持标识符、字符串、注释和括号层级；解析失败应明确抛错，ODS 固定列与引擎参数不得被静默丢失。
 */

// 创建表的纯逻辑(与 Vue 解耦,便于单元测试)。
// 所有函数无副作用,输入输出确定,可 100% 覆盖。


import { escapeSqlString, isSafeColumnType, isSafeDefaultExpr } from '@/utils/ddlGuard'
export interface ColumnRow {
  name: string
  type: string
  nullable: boolean
  defaultValue: string
  comment: string
  /** 原字段(字段映射的只读来源,如从数据源导入时的源列名) */
  orig?: string
  /**
   * 系统自动追加的保留列(mdn / ods_insert_at / ods_source_id)。
   * 只用来判定"该行是否只读(置灰)":按行标记而不是按名字 —— 否则用户自己再写一个
   * 叫 mdn 的业务列时,会因为名字命中保留列被一并置灰(见 CreateTableDialog.isOdsRo)。
   */
  _odsSys?: boolean
}

/** 创建表对话框的完整状态(由组件 ref 组装传入纯函数) */
export interface TableState {
  db: string
  tableName: string
  tableRemark: string
  engine: string
  partitionExpr: string
  sortingKey: string[]
  primaryKey: string[]
  columns: ColumnRow[]
  paramSign: string
  paramVersion: string
  paramSumming: string[]
}

/** ODS 贴源表自动追加的字段:入库时间 + 来源(来源 = 数据源唯一标识) */
export const ODS_COLS: ColumnRow[] = [
  { name: 'ods_insert_at', type: 'DateTime', nullable: false, defaultValue: 'now()', comment: '入库时间' },
  { name: 'ods_source_id', type: 'String', nullable: false, defaultValue: '', comment: '来源(数据源标识)' },
]

/** 常用日期分区函数 */
export const PARTITION_FNS = ['toDate', 'toMonday', 'toStartOfMonth', 'toStartOfDay', 'toYYYYMM', 'toYYYYMMDD', 'toYYYYMMDDhhmmss']

/** 支持的引擎 */
export const ENGINE_OPTIONS = [
  'MergeTree',
  'ReplacingMergeTree',
  'SummingMergeTree',
  'AggregatingMergeTree',
  'CollapsingMergeTree',
  'VersionedCollapsingMergeTree',
  'Log',
  'TinyLog',
  'StripeLog',
  'Memory',
]

/** 支持的字段类型 */
export const TYPE_OPTIONS = [
  'String', 'FixedString(16)',
  'Int8', 'Int16', 'Int32', 'Int64', 'Int128', 'Int256',
  'UInt8', 'UInt16', 'UInt32', 'UInt64', 'UInt128', 'UInt256',
  'Float32', 'Float64', 'Bool',
  'Date', 'Date32', 'DateTime', 'DateTime64(3)',
  'UUID', 'IPv4', 'IPv6', 'Decimal(18, 4)',
  'JSON', 'Map(String, String)', 'Array(String)', 'LowCardinality(String)',
  "Enum8('a' = 1)", "Enum16('a' = 1)", 'Tuple(Int32)',
]

/** 需要/可带引擎参数的引擎 */
export function engineNeedsParam(engine: string): boolean {
  return ['CollapsingMergeTree', 'VersionedCollapsingMergeTree', 'ReplacingMergeTree', 'SummingMergeTree'].includes(engine)
}

/** 是否 MergeTree 系引擎 */
export function isMergeTree(engine: string): boolean {
  return engine.endsWith('MergeTree')
}

/** ODS 前缀规范化:无论输入 ods、ods_、ods-ods、odsods_ 等,统一为一个 ods_ 前缀 */
export function normalizeOdsName(name: string): string {
  const trimmed = name.trim()
  // 第一步剥掉开头任意组合/重复的 ods 前缀(ods_ / ods- / ods. / odsods)
  const base = trimmed.replace(/^(?:ods[_\-.]?)+/i, '')
  // 第二步把残留的分隔符也去掉,避免出现 ods__name 这种双下划线
  const cleanBase = base.replace(/^[_\-.\s]+/, '')
  return 'ods_' + cleanBase
}

/**
 * 用反引号包裹标识符,并转义内部的 `。
 *
 * ⚠️ 以前只包一层反引号、不转义内容 —— 名字里带一个反引号就能提前闭合标识符,
 * 把后面的内容当成 SQL 结构改写掉(评审 security,已核实)。
 * 转义写法是【双反引号】,不是反斜杠:实测 `SELECT 1 AS \`a``b\`` → 列名 a`b` 正确,
 * 而裸拼同一条直接 400。
 * 这是本文件里所有拼标识符的地方共用的入口 —— 包括 generateDDL 的
 * `CREATE TABLE ... <db>.<name>`(以前它是自己手拼反引号的,评审 r4 指出后已改)。
 * ⚠️ 但 TableDetail.vue 的 ALTER 预览仍在自己拼(那里拼的是 props.db/props.table/
 *    用户输入的列名),那处没有走这里 —— 别再把这句话当成"全仓库都没问题"。
 */
export function quoteIdent(name: string): string {
  return '`' + name.replace(/`/g, '``') + '`'
}

/** 列名数组 → ORDER BY/PRIMARY KEY 表达式 */
export function exprFor(cols: string[]): string {
  const q = cols.map(quoteIdent)
  return q.length === 1 ? q[0] : `(${q.join(', ')})`
}

/** 分区表达式格式化:纯字段名加反引号,函数表达式原样输出 */
export function formatPartition(expr: string): string {
  if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(expr)) return quoteIdent(expr)
  return expr
}

/** 判断 prim 是否为 sort 的前缀(ClickHouse 要求 PRIMARY KEY 是 ORDER BY 的前缀) */
export function isPrefix(prim: string[], sort: string[]): boolean {
  // 逐位比对而不是用集合:顺序也必须一致,(a,b) 与 (b,a) 不是前缀关系
  return prim.length <= sort.length && prim.every((k, i) => sort[i] === k)
}

/** 由表格配置生成 DDL */
export function generateDDL(state: TableState): string {
  const name = state.tableName.trim()
  // 表格里允许有没填名字的空行(刚点「添加字段」还没写),拼 DDL 前先丢掉
  const cols = state.columns.filter((c) => c.name.trim())
  const colDefs = cols.map((c) => {
    let type = c.type.trim() || 'String'
    // 「可空」开关落到 DDL 上就是 Nullable(...) 包装;已包过的不重复包
    if (c.nullable && !type.startsWith('Nullable(')) type = `Nullable(${type})`
    let def = `    ${quoteIdent(c.name.trim())} ${type}`
    if (c.defaultValue.trim()) {
      def += ` DEFAULT ${c.defaultValue.trim()}`
    }
    if (c.comment.trim()) {
      // 注释里的单引号必须转义,否则会把 COMMENT 字符串提前闭合
      def += ` COMMENT '${escapeSqlString(c.comment.trim())}'`
    }
    return def
  })

  const lines: string[] = []
  if (!name) return '-- 请先填写表名'
  // IF NOT EXISTS:预览语句可能被用户直接粘贴执行,避免二次执行报错
  lines.push(`CREATE TABLE IF NOT EXISTS ${quoteIdent(state.db)}.${quoteIdent(name)}`)
  if (colDefs.length) {
    lines.push('(')
    lines.push(colDefs.join(',\n'))
    lines.push(')')
  } else {
    // 没有字段时仍然给出可读的占位注释,而不是拼出语法非法的 CREATE TABLE ()
    lines.push('-- 请至少添加一个字段')
  }
  // 引擎参数(如 CollapsingMergeTree(Sign))仅在需要时附加
  const engineParamValue = engineParamValueOf(state)
  const eng = state.engine + (isMergeTree(state.engine) && engineParamValue.trim() ? `(${engineParamValue.trim()})` : '')
  if (isMergeTree(state.engine)) {
    lines.push(`ENGINE = ${eng}`)
    const partExpr = state.partitionExpr.trim()
    // 排序/主键只保留仍然存在的列:删掉列后残留的键名直接拼进去会让建表失败
    const sort = state.sortingKey.filter((k) => colNameOptionsOf(state).includes(k))
    const prim = state.primaryKey.filter((k) => colNameOptionsOf(state).includes(k))
    if (partExpr) lines.push(`PARTITION BY ${formatPartition(partExpr)}`)
    if (sort.length) lines.push(`ORDER BY ${exprFor(sort)}`)
    if (prim.length) lines.push(`PRIMARY KEY ${exprFor(prim)}`)
  } else {
    // Log/TinyLog/Memory 等非 MergeTree 引擎不接受分区与排序键
    lines.push(`ENGINE = ${state.engine}`)
  }
  if (state.tableRemark.trim()) {
    lines.push("COMMENT '" + escapeSqlString(state.tableRemark.trim()) + "'")
  }
  return lines.join('\n')
}

/** 引擎参数值(由引擎类型 + 参数子字段决定) */
export function engineParamValueOf(state: TableState): string {
  switch (state.engine) {
    case 'CollapsingMergeTree':
      return state.paramSign
    case 'VersionedCollapsingMergeTree':
      return [state.paramSign, state.paramVersion].filter(Boolean).join(', ')
    case 'ReplacingMergeTree':
      return state.paramVersion
    case 'SummingMergeTree':
      return state.paramSumming.join(', ')
    default:
      return ''
  }
}

/** 列名列表(去空) */
export function colNameOptionsOf(state: TableState): string[] {
  return state.columns.map((c) => c.name.trim()).filter(Boolean)
}

/** 分区键候选:纯字段 + 各函数应用 */
export function partitionOptionsOf(state: TableState): string[] {
  const opts: string[] = []
  for (const c of colNameOptionsOf(state)) {
    opts.push(c)
    for (const fn of PARTITION_FNS) opts.push(`${fn}(${c})`)
  }
  return opts
}

/**
 * 字段名重复检测:同名两列建表必失败(ClickHouse 直接报重复列)。
 * 返回提示或 null;供 grid 校验与创建表对话框共用同一套判定。
 */
export function duplicateColumnError(columns: ColumnRow[]): string | null {
  const seen = new Set<string>()
  for (const c of columns) {
    const n = c.name.trim()
    if (!n) continue
    if (seen.has(n)) return `字段名「${n}」重复,请改用唯一的列名`
    seen.add(n)
  }
  return null
}

/** 表格方式校验,返回错误消息;通过返回 null */
export function validateGrid(state: TableState): string | null {
  const name = state.tableName.trim()
  if (!name) return '请输入表名'
  // 只放行裸标识符:带反引号/点号的表名交给 SQL 方式,表格方式不许拼出跨库路径
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) return '表名仅允许字母/数字/下划线,且不能以数字开头'
  const cols = state.columns.filter((c) => c.name.trim())
  if (!cols.length) return '请至少添加一个字段'
  // 字段名不允许重复:同名两列建表必失败(ClickHouse 会直接报重复列)。提前拦下并点明是哪个名字
  const dup = duplicateColumnError(state.columns)
  if (dup) return dup
  // 逐个字段校验,发现第一个问题就返回:一次只提示一条,避免刷屏
  for (const c of cols) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(c.name.trim())) return `字段名非法:${c.name}`
    if (!c.type.trim()) return `字段「${c.name}」缺少类型`
    /*
      ⚠️ 类型与默认值以前只检查"非空",然后原样拼进 CREATE TABLE(评审 r5 #6)。
      与 TableDetail 的 ALTER 路径同类,统一走 utils/ddlGuard(与服务端同规则)。
    */
    if (!isSafeColumnType(c.type)) return `字段「${c.name}」类型非法:${c.type}`
    if (c.defaultValue && !isSafeDefaultExpr(c.defaultValue)) return `字段「${c.name}」默认值非法:${c.defaultValue}`
  }
  if (isMergeTree(state.engine)) {
    const sort = state.sortingKey.filter((k) => colNameOptionsOf(state).includes(k))
    // MergeTree 没有 ORDER BY 建不出来,这里必须拦,不能交给后端报错
    if (!sort.length) return 'MergeTree 引擎必须指定排序键(ORDER BY)'
    const prim = state.primaryKey.filter((k) => colNameOptionsOf(state).includes(k))
    // 若把「已删除列」算进来会误判前缀关系,所以上面先按现存列过滤一遍
    if (prim.length && !isPrefix(prim, sort)) return '主键(PRIMARY KEY)必须是排序键(ORDER BY)的前缀'
    // ClickHouse 禁止可空列直接作排序/主键(排序键含 nullable 会建表失败)
    const keyCols = new Set([...sort, ...prim])
    for (const c of cols) {
      if (keyCols.has(c.name.trim()) && c.nullable) {
        return `键列「${c.name.trim()}」不能为可空;请关闭其“可空”开关,或改用其它列作排序/主键`
      }
    }
  }
  if (state.engine === 'CollapsingMergeTree' && !state.paramSign) {
    return 'CollapsingMergeTree 需要选择标记字段(Sign)'
  }
  if (state.engine === 'VersionedCollapsingMergeTree' && (!state.paramSign || !state.paramVersion)) {
    return 'VersionedCollapsingMergeTree 需要选择 Sign 和 Version 字段'
  }
  return null
}

/* ==================== DDL 识别 ==================== */

/** 返回 s 中从 openIdx 起的左括号匹配的右括号下标,找不到返回 -1 */
export function findMatchingParen(s: string, openIdx: number): number {
  // 纯字符计深,不解析字符串字面量:DDL 里默认值带括号的情况由调用方另行处理
  let depth = 0
  for (let i = openIdx; i < s.length; i++) {
    if (s[i] === '(') depth++
    else if (s[i] === ')') {
      depth--
      if (depth === 0) return i
    }
  }
  // 括号没闭合(残缺 DDL),由调用方决定是报错还是放弃
  return -1
}

/** 在括号深度 0 处按分隔符切分 */
export function splitTopLevel(s: string, delim: string): string[] {
  // 只在 depth===0 处切:Decimal(18, 4)、Map(String, String) 里的逗号不算分隔符,
  // 否则一个字段会被切成两半
  const parts: string[] = []
  let depth = 0
  let start = 0
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (ch === '(') depth++
    else if (ch === ')') depth--
    else if (ch === delim && depth === 0) {
      parts.push(s.slice(start, i))
      start = i + 1
    }
  }
  parts.push(s.slice(start))
  return parts
}

const COL_MOD_KW = ['DEFAULT', 'MATERIALIZED', 'ALIAS', 'COMMENT', 'CODEC', 'TTL', 'PRIMARY KEY', 'UNIQUE KEY']

/** 返回 s 中括号深度 0 处最先出现的关键字下标,找不到返回 -1 */
export function findFirstTopLevelKeyword(s: string, keywords: string[]): number {
  let depth = 0
  const upper = s.toUpperCase()
  let best = -1
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (ch === '(') depth++
    else if (ch === ')') depth--
    else if (depth === 0) {
      for (const kw of keywords) {
        if (upper.startsWith(kw, i)) {
          // 前一个字符必须是分隔符:否则 xxxCOMMENT 这类名字会被误判成关键字
          const prev = i > 0 ? s[i - 1] : ' '
          // 保留最靠前的一处:先遇到的关键字才是「类型结束」的位置
          if (!/[A-Za-z0-9_]/.test(prev) && (best === -1 || i < best)) best = i
        }
      }
    }
  }
  return best
}

const CLAUSE_START = ['INDEX', 'CONSTRAINT', 'PRIMARY KEY', 'UNIQUE KEY', 'PROJECTION', 'CHECK', 'TTL']

/** 判断字段段是否为 INDEX/CONSTRAINT 等子句而非列定义 */
export function isClauseSegment(seg: string): boolean {
  const up = seg.trim().toUpperCase()
  return CLAUSE_START.some((k) => up.startsWith(k))
}

/** 与 escapeSqlString 对称的还原:单遍扫描 */
export function unescapeSqlString(s: string): string {
  let out = ''
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\' && i + 1 < s.length) {
      out += s[i + 1]
      i++
      continue
    }
    if (s[i] === "'" && s[i + 1] === "'") {
      out += "'"
      i++
      continue
    }
    out += s[i]
  }
  return out
}

/** 解析一条列定义：提取字段名、类型（含 Nullable）、默认值和注释。 */
export function parseColumnDef(seg: string): ColumnRow {
  const s = seg.trim()
  // 名字允许带反引号;匹配不到则 name 为空串,由调用方决定丢弃该段
  const nameMatch = s.match(/^\s*`?([A-Za-z_][A-Za-z0-9_]*)`?/)
  const name = nameMatch ? nameMatch[1] : ''
  const rest = nameMatch ? s.slice(nameMatch[0].length) : s
  // 类型本身可能含括号(Decimal(18, 4)),所以要用「顶层关键字」定位修饰段起点
  const kwIdx = findFirstTopLevelKeyword(rest, COL_MOD_KW)
  let type = (kwIdx >= 0 ? rest.slice(0, kwIdx) : rest).trim()
  const tail = kwIdx >= 0 ? rest.slice(kwIdx).trim() : ''
  let nullable = type.toUpperCase().startsWith('NULLABLE(')
  // 只有整串就是一个 Nullable(...) 时才剥壳;Array(Nullable(X)) 这类保持原样
  if (nullable && /^NULLABLE\(.*\)$/i.test(type)) {
    type = type.slice(9, -1).trim() // 去掉外层 Nullable(),避免重复包裹
  }
  let defaultValue = ''
  // 默认值表达式可能自带括号与空格(now()、toDate('2024-01-01')),截到下一个修饰关键字为止
  const defMatch = tail.match(/DEFAULT\s+(.*?)(?=\s+(?:COMMENT|CODEC|TTL|MATERIALIZED|ALIAS)\b|$)/is)
  if (defMatch) defaultValue = defMatch[1].trim()
  let comment = ''
  /*
    ⚠️ 两处都踩过(评审 r8 提了、r9 指出没生效):
    ① 还原必须【对捕获组】做,不能放在 let comment = '' 之后 —— 那样是对空串调用,
       紧接着 if (comMatch) 又用未还原的原值覆盖,等于死代码;
    ② 正则必须容纳【成对引号】:escapeSqlString 把 ' 加倍成 '',而 [^']* 会在第一个 ' 处
       截断(it''s 被解析成 it)。所以用 (?:[^']|'')* 匹配,再统一反向还原。
  */
  const comMatch = tail.match(/COMMENT\s+(?:'((?:[^']|'')*)'|"([^"]*)")/)
  if (comMatch) {
    const raw = comMatch[1] !== undefined ? comMatch[1] : comMatch[2]
    comment = unescapeSqlString(raw.trim())
  }
  return { name, type, nullable, defaultValue, comment }
}

const NEXT_CLAUSE = ['ENGINE', 'PARTITION BY', 'ORDER BY', 'PRIMARY KEY', 'SAMPLE BY', 'SETTINGS', 'TTL', 'INDEX']

/** 取 tail 中 keyword 后的表达式,截到下一个子句关键字为止 */
export function extractClauseValue(tail: string, keyword: string): string {
  const idx = tail.toUpperCase().indexOf(keyword)
  if (idx < 0) return ''
  let rest = tail.slice(idx + keyword.length).trim()
  const stop = findFirstTopLevelKeyword(rest, NEXT_CLAUSE)
  if (stop >= 0) rest = rest.slice(0, stop)
  return rest.trim()
}

/** 从键表达式提取列名:支持 (a, b)、函数包裹如 toYYYYMM(created) */
export function extractKeyColumns(expr: string): string[] {
  let s = expr.trim()
  // 多列键写成 (a, b),先剥掉最外层括号再按顶层逗号拆
  if (s.startsWith('(') && s.endsWith(')')) s = s.slice(1, -1).trim()
  const cols: string[] = []
  for (const raw of splitTopLevel(s, ',')) {
    const t = raw.trim()
    if (!t) continue
    let id = ''
    const fnMatch = t.match(/^[A-Za-z_][A-Za-z0-9_]*\s*\(/)
    if (fnMatch) {
      // 函数名本身是 tuple 时跳过(如 tuple(a, b) 不是列引用)
      const fnName = fnMatch[0].replace(/[^A-Za-z0-9_]/g, '').toLowerCase()
      if (fnName === 'tuple') continue
      const inner = t.slice(t.indexOf('(') + 1, t.lastIndexOf(')')).trim()
      const m = inner.match(/`?([A-Za-z_][A-Za-z0-9_]*)`?/)
      if (m) id = m[1]
    } else {
      const m = t.match(/`?([A-Za-z_][A-Za-z0-9_]*)`?/)
      if (m) id = m[1]
    }
    if (id && id.toLowerCase() !== 'tuple') cols.push(id)
  }
  return cols
}

export interface ParsedDDL {
  tableName: string
  engine: string
  engineParam: string
  partitionExpr: string
  sortingKey: string[]
  primaryKey: string[]
  columns: ColumnRow[]
}

/** 解析 CREATE TABLE 语句,识别为表格方式所需的结构 */
export function parseCreateDDL(ddl: string): ParsedDDL {
  const src = ddl.trim()
  const createMatch = src.match(
    /CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+(?:`?[A-Za-z_][A-Za-z0-9_]*`?\s*\.\s*)?`?([A-Za-z_][A-Za-z0-9_]*)`?/i,
  )
  if (!createMatch) throw new Error('未识别到 CREATE TABLE 语句')
  const tableName = createMatch[1]

  const openParen = src.indexOf('(')
  if (openParen < 0) throw new Error('未找到字段定义')
  const closeParen = findMatchingParen(src, openParen)
  if (closeParen < 0) throw new Error('字段定义括号不匹配')
  // body = 括号内的字段定义;tail = 括号之后的引擎/分区/排序等子句
  const body = src.slice(openParen + 1, closeParen)
  const tail = src.slice(closeParen + 1)

  // 引擎与引擎参数
  let engine = 'MergeTree'
  let engineParam = ''
  const engMatch = tail.match(/ENGINE\s*=\s*([A-Za-z_][A-Za-z0-9_]*)/i)
  if (engMatch) {
    engine = engMatch[1]
    const afterEngine = tail.slice(tail.indexOf(engMatch[0]) + engMatch[0].length)
    if (afterEngine.trimStart().startsWith('(')) {
      const p = findMatchingParen(afterEngine, afterEngine.indexOf('('))
      if (p >= 0) engineParam = afterEngine.slice(afterEngine.indexOf('(') + 1, p).trim()
    }
  }

  const partitionExpr = extractClauseValue(tail, 'PARTITION BY')
  const orderExpr = extractClauseValue(tail, 'ORDER BY')
  const primaryExpr = extractClauseValue(tail, 'PRIMARY KEY')

  const columns: ColumnRow[] = []
  for (const seg of splitTopLevel(body, ',')) {
    const t = seg.trim()
    // 空段(尾随逗号)与 INDEX/CONSTRAINT 等子句都不是列定义,跳过
    if (!t || isClauseSegment(t)) continue
    const col = parseColumnDef(t)
    if (col.name) columns.push(col)
  }
  // 解析不出任何字段说明这不是一份可用的建表语句,宁可报错也不要回填一张空表
  if (!columns.length) throw new Error('未解析到字段')

  return {
    tableName,
    engine,
    engineParam,
    partitionExpr,
    sortingKey: extractKeyColumns(orderExpr),
    primaryKey: extractKeyColumns(primaryExpr),
    columns,
  }
}

/** 按引擎把 DDL 解析出的引擎参数填入对应字段(返回新状态字段) */
export function parseEngineParams(engine: string, raw: string): { paramSign: string; paramVersion: string; paramSumming: string[] } {
  const parts = raw.split(',').map((s) => s.trim()).filter(Boolean)
  switch (engine) {
    case 'CollapsingMergeTree':
      return { paramSign: parts[0] ?? '', paramVersion: '', paramSumming: [] }
    case 'VersionedCollapsingMergeTree':
      return { paramSign: parts[0] ?? '', paramVersion: parts[1] ?? '', paramSumming: [] }
    case 'ReplacingMergeTree':
      return { paramSign: '', paramVersion: parts[0] ?? '', paramSumming: [] }
    case 'SummingMergeTree':
      return { paramSign: '', paramVersion: '', paramSumming: parts }
    default:
      return { paramSign: '', paramVersion: '', paramSumming: [] }
  }
}

/* ==================== DDL 预览高亮 ==================== */

const SQL_KEYWORDS = [
  'CREATE', 'TABLE', 'IF', 'NOT', 'EXISTS', 'ENGINE', 'PARTITION', 'BY', 'ORDER', 'PRIMARY', 'KEY',
  'DEFAULT', 'COMMENT', 'CODEC', 'TTL', 'NULLABLE', 'MERGETREE', 'REPLACINGMERGETREE', 'SUMMINGMERGETREE',
  'AGGREGATINGMERGETREE', 'COLLAPSINGMERGETREE', 'VERSIONEDCOLLAPSINGMERGETREE', 'LOG', 'TINYLOG',
  'STRIPELOG', 'MEMORY', 'SELECT', 'FROM', 'WHERE', 'INSERT', 'INTO', 'VALUES', 'AND', 'OR', 'AS',
  'WITH', 'ON', 'UNION', 'ALL', 'DISTINCT', 'GROUP', 'HAVING', 'LIMIT', 'OFFSET', 'JOIN', 'LEFT',
  'RIGHT', 'INNER', 'OUTER', 'SETTINGS', 'MATERIALIZED', 'ALIAS', 'SAMPLE', 'INDEX', 'CONSTRAINT',
  'PROJECTION', 'CHECK', 'UNIQUE', 'FINAL', 'SYNC', 'TO', 'USING', 'PREWHERE', 'ARRAY', 'MAP', 'TUPLE',
]

const SQL_TYPES = [
  'String', 'FixedString', 'Int8', 'Int16', 'Int32', 'Int64', 'Int128', 'Int256',
  'UInt8', 'UInt16', 'UInt32', 'UInt64', 'UInt128', 'UInt256',
  'Float32', 'Float64', 'Bool', 'Date', 'Date32', 'DateTime', 'DateTime64',
  'UUID', 'IPv4', 'IPv6', 'Decimal', 'JSON', 'LowCardinality', 'Enum8', 'Enum16', 'Nullable', 'Array', 'Map', 'Tuple',
]

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 轻量 SQL 语法高亮,返回 HTML 字符串(供 v-html 使用)。 */
export function highlightDDL(ddl: string): string {
  if (!ddl) return ''
  const keywordSet = new Set(SQL_KEYWORDS)
  const typeSet = new Set(SQL_TYPES.map((t) => t.toUpperCase()))
  const tokens: string[] = []
  let i = 0
  const n = ddl.length

  while (i < n) {
    const ch = ddl[i]

    // 行注释 --
    // 顺序即优先级:注释必须排在数字/运算符判断之前,否则连字符会被当成符号吃掉
    if (ch === '-' && ddl[i + 1] === '-') {
      let j = i + 2
      while (j < n && ddl[j] !== '\n') j++
      tokens.push(`<span class="hl-comment">${escapeHtml(ddl.slice(i, j))}</span>`)
      i = j
      continue
    }
    // 块注释 /* */
    if (ch === '/' && ddl[i + 1] === '*') {
      const end = ddl.indexOf('*/', i + 2)
      const j = end >= 0 ? end + 2 : n
      tokens.push(`<span class="hl-comment">${escapeHtml(ddl.slice(i, j))}</span>`)
      i = j
      continue
    }
    // 字符串
    // 三种引号一起处理:反引号是标识符,单/双引号是字符串,只是最后上的 class 不同
    if (ch === "'" || ch === '"' || ch === '`') {
      const quote = ch
      let j = i + 1
      let buf = quote
      while (j < n) {
        if (ddl[j] === '\\' && j + 1 < n) {
          buf += ddl[j] + ddl[j + 1]
          j += 2
          continue
        }
        buf += ddl[j]
        if (ddl[j] === quote) {
          // 双引号转义 '' 或 ""
          if (j + 1 < n && ddl[j + 1] === quote) {
            buf += ddl[j + 1]
            j += 2
            continue
          }
          j++
          break
        }
        j++
      }
      const cls = quote === '`' ? 'hl-ident' : 'hl-string'
      tokens.push(`<span class="${cls}">${escapeHtml(buf)}</span>`)
      i = j
      continue
    }
    // 数字
    // 前导小数点也算数字(.5),否则会被当普通字符逐个输出
    if (/[0-9]/.test(ch) || (ch === '.' && /[0-9]/.test(ddl[i + 1] ?? ''))) {
      let j = i
      while (j < n && /[0-9.eE+\-]/.test(ddl[j])) {
        // 避免吃掉 1+2 里的 +
        // 只有紧跟在 e/E 之后的 +/- 才是指数符号(1e-3)
        if (ddl[j] === '+' || ddl[j] === '-') {
          if (j > i && !/[eE]/.test(ddl[j - 1])) break
        }
        j++
      }
      tokens.push(`<span class="hl-number">${escapeHtml(ddl.slice(i, j))}</span>`)
      i = j
      continue
    }
    // 标识符 / 关键字 / 类型
    if (/[A-Za-z_]/.test(ch)) {
      let j = i
      while (j < n && /[A-Za-z0-9_]/.test(ddl[j])) j++
      const word = ddl.slice(i, j)
      const upper = word.toUpperCase()
      // 关键字优先于类型:像 ARRAY/MAP/TUPLE 两边都在,按关键字样式显示
      if (keywordSet.has(upper)) {
        tokens.push(`<span class="hl-keyword">${escapeHtml(word)}</span>`)
      } else if (typeSet.has(upper) || typeSet.has(upper.replace(/\(\d+(,\s*\d+)?\)$/, ''))) {
        // 带精度参数的类型(DateTime64(3))去掉参数再与类型表比对
        tokens.push(`<span class="hl-type">${escapeHtml(word)}</span>`)
      } else {
        tokens.push(escapeHtml(word))
      }
      i = j
      continue
    }
    // 普通字符
    // 兜底分支:空格/标点等一律转义后原样输出(片段来自用户输入,必须防 HTML 注入)
    tokens.push(escapeHtml(ch))
    i++
  }
  return tokens.join('')
}
