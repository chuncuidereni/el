/**
 * sqlHighlight.ts
 *
 * 职责：SQL 文本高亮：把关键字、类型、函数、数字、字符串和注释转成安全 HTML。
 * 边界：输入必须先 HTML 转义再分词，避免执行用户文本；输出仅用于受控 v-html 展示。
 */

/**
 * 轻量 ClickHouse SQL 语法高亮。
 *
 * 用途:给「生成的 SQL」「本次执行的 SQL」这类**只读小片段**上色。
 * 为什么不用 CodeMirror:这些片段每次配置变动都会重渲染,每个都塞一个 EditorView 太重。
 * 这里只做静态 tokenize,输出已转义的 HTML,配色与 CodeMirrorEditor 保持一致。
 *
 * ⚠️ 返回的是 HTML 字符串,用于 v-html。所有原文都经过 escapeHtml,不存在注入风险。
 */

const KEYWORDS = new Set([
  // 查询
  'SELECT', 'FROM', 'WHERE', 'GROUP', 'BY', 'ORDER', 'HAVING', 'LIMIT', 'OFFSET', 'WITH',
  'AS', 'DISTINCT', 'ALL', 'UNION', 'EXCEPT', 'INTERSECT', 'SAMPLE', 'FINAL', 'PREWHERE',
  'QUALIFY', 'WINDOW', 'OVER', 'PARTITION', 'ROWS', 'RANGE', 'PRECEDING', 'FOLLOWING',
  'UNBOUNDED', 'CURRENT', 'ROW', 'ARRAY', 'JOIN', 'ON', 'USING', 'LEFT', 'RIGHT', 'INNER',
  'OUTER', 'FULL', 'CROSS', 'ANY', 'ASOF', 'GLOBAL', 'SETTINGS', 'FORMAT', 'INTO',
  // 逻辑 / 条件
  'AND', 'OR', 'NOT', 'IN', 'IS', 'NULL', 'LIKE', 'ILIKE', 'BETWEEN', 'CASE', 'WHEN',
  'THEN', 'ELSE', 'END', 'EXISTS', 'INTERVAL', 'ASC', 'DESC', 'NULLS', 'FIRST', 'LAST',
  // DDL / DML(片段里可能带)
  'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'DROP', 'TABLE', 'VIEW', 'DATABASE', 'IF',
  'ENGINE', 'TTL', 'ATTACH', 'DETACH', 'ALTER', 'RENAME', 'OPTIMIZE',
])

const TYPES = new Set([
  'STRING', 'FIXEDSTRING', 'UINT8', 'UINT16', 'UINT32', 'UINT64', 'UINT128', 'UINT256',
  'INT8', 'INT16', 'INT32', 'INT64', 'INT128', 'INT256', 'FLOAT32', 'FLOAT64', 'BOOL',
  'BOOLEAN', 'DATE', 'DATE32', 'DATETIME', 'DATETIME64', 'DECIMAL', 'UUID', 'ARRAY',
  'TUPLE', 'MAP', 'NULLABLE', 'LOWCARDINALITY', 'ENUM8', 'ENUM16', 'IPV4', 'IPV6',
])

const BOOLS = new Set(['TRUE', 'FALSE'])

// 常见函数:用于「标识符后有 (」时的兜底判断之外,识别无括号的聚合/函数名
const KNOWN_FUNCS = new Set([
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'MEDIAN', 'UNIQ', 'UNIQEXACT', 'UNIQCOMBINED',
  'GROUPARRAY', 'GROUPUNIQARRAY', 'TOPK', 'QUANTILE', 'ARGMAX', 'ARGMIN', 'ANY', 'ANYLAST',
  'COUNTIF', 'SUMIF', 'AVGIF',
])

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 包一层带 class 的 span(内容已转义) */
function span(cls: string, text: string): string {
  return `<span class="sql-${cls}">${escapeHtml(text)}</span>`
}

// 依次匹配:行注释 | 块注释 | 单引号字符串 | 反引号标识符 | 双引号标识符 |
//           数字 | 标识符 | 运算符/标点
const TOKEN_RE =
  /(--[^\n]*|\/\*[\s\S]*?\*\/)|('(?:[^']|'')*')|(`(?:[^`]|``)*`)|("(?:[^"]|"")*")|\b(\d+(?:\.\d+)?)\b|([A-Za-z_][A-Za-z0-9_]*)|(::|<=|>=|<>|!=|\|\||[=<>+\-*/%.,();[\]{}|])/g

/** 把 SQL 转成带高亮 span 的 HTML(内容已转义) */
export function highlightSql(src: string): string {
  if (!src) return ''
  let out = ''
  let last = 0
  let m: RegExpExecArray | null
  TOKEN_RE.lastIndex = 0

  while ((m = TOKEN_RE.exec(src)) !== null) {
    if (m.index > last) out += escapeHtml(src.slice(last, m.index))

    if (m[1]) {
      out += span('c', m[1]) // 注释
    } else if (m[2]) {
      out += span('s', m[2]) // 单引号字符串
    } else if (m[3] || m[4]) {
      out += span('i', m[3] || m[4]) // 反引号 / 双引号标识符
    } else if (m[5]) {
      out += span('n', m[5]) // 数字
    } else if (m[6]) {
      const word = m[6]
      const upper = word.toUpperCase()
      // 紧跟 `(` 且不是关键字 → 视作函数调用
      // ⚠️ 只取后面几个字符判断:整个 slice 到末尾在长 SQL 上是 O(n²)
      const after = src.slice(m.index + word.length, m.index + word.length + 8)
      const isCall = /^\s*\(/.test(after)
      if (KEYWORDS.has(upper)) out += span('k', word)
      else if (BOOLS.has(upper)) out += span('b', word)
      else if (TYPES.has(upper)) out += span('t', word)
      else if (isCall || KNOWN_FUNCS.has(upper)) out += span('f', word)
      else out += escapeHtml(word) // 普通标识符(表名/列名/别名)
    } else if (m[7]) {
      out += span('o', m[7]) // 运算符 / 标点
    }

    last = m.index + m[0].length
  }
  if (last < src.length) out += escapeHtml(src.slice(last))
  return out
}
