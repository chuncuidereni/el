/**
 * ddlGuard.ts
 *
 * 职责：DDL 片段安全校验：限制列类型和默认表达式，并统一转义注释中的反斜杠与单引号。
 *
 * 规则与后端 internal/service/ddl_guard.go 逐条对应。前端校验只用于即时提示，服务端仍是安全边界。
 * ClickHouse 允许一条 ALTER 用逗号分隔多个动作，因此必须按括号深度扫描并禁止顶层逗号。
 * 规则也不能过窄：负值 Enum、带时区 DateTime64、now64/toDate 和科学计数法都是合法值，
 * 否则回读已有列并只修改注释时，也会被整体拒绝。
 */

/** 通用片段扫描:括号配平 / 字符串整段跳过 / 顶层禁逗号 / 禁 ; " ` \ 换行 与注释符 */
function safeFragment(s: string): boolean {
  if (!s.trim()) return false
  let depth = 0
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (ch === "'") {
      i++
      let closed = false
      for (; i < s.length; i++) {
        if (s[i] === '\\') {
          i++
          continue
        }
        if (s[i] === "'") {
          if (s[i + 1] === "'") {
            i++
            continue
          }
          closed = true
          break
        }
      }
      if (!closed) return false
      continue
    }
    if (ch === '(') {
      depth++
    } else if (ch === ')') {
      depth--
      if (depth < 0) return false
    } else if (ch === ',') {
      if (depth === 0) return false
    } else if (ch === ';' || ch === '"' || ch === '`' || ch === '\\' || ch === '\n' || ch === '\r') {
      return false
    } else if (ch === '-' && s[i + 1] === '-') {
      return false
    } else if (ch === '/' && s[i + 1] === '*') {
      return false
    } else if (!/[A-Za-z0-9_.(),= -]/.test(ch)) {
      return false
    }
  }
  return depth === 0
}

// ⚠️ 必须与 Go 的 numLitRe 逐字一致(评审 r7 #4/#6:两边曾经一个严一个松)
const NUM_LIT = /^[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?$/
// ⚠️ 与 Go 复用的 identRe 一致:不带点(评审 r7 #7)
const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/

/** 整串是否恰好是一个单引号字面量(结尾后不能有内容) */
function isSingleStringLiteral(s: string): boolean {
  if (s.length < 2 || s[0] !== "'") return false
  for (let i = 1; i < s.length; i++) {
    if (s[i] === '\\') {
      i++
      continue
    }
    if (s[i] === "'") {
      if (s[i + 1] === "'") {
        i++
        continue
      }
      return i === s.length - 1
    }
  }
  return false
}

/** 列类型片段能否安全拼进 ALTER(尾锚定:含括号时必须以 ) 结尾) */
export function isSafeColumnType(t: string): boolean {
  const s = (t ?? '').trim()
  if (!safeFragment(s)) return false
  const i = s.indexOf('(')
  if (i < 0) return IDENT.test(s)
  // ⚠️ 尾锚定:找与第一个 ( 配对的那个 ) 再判断(Vue 与 Go 同规则)
  return IDENT.test(s.slice(0, i).trim()) && matchingParen(s, i) === s.length - 1
}

/** 返回与 s[i]('(')配对的 ')' 下标;找不到返回 -1。跳过单引号字面量 */
function matchingParen(s: string, i: number): number {
  let depth = 0
  for (let j = i; j < s.length; j++) {
    const c = s[j]
    if (c === "'") {
      j++
      for (; j < s.length; j++) {
        if (s[j] === '\\') {
          j++
        } else if (s[j] === "'") {
          if (s[j + 1] !== "'") break
          j++
        }
      }
      continue
    }
    if (c === '(') depth++
    else if (c === ')') {
      depth--
      if (depth === 0) return j
    }
  }
  return -1
}

/** DEFAULT 表达式能否安全拼进 ALTER(整串匹配,尾锚定) */
export function isSafeDefaultExpr(v: string): boolean {
  const s = (v ?? '').trim()
  if (!safeFragment(s)) return false
  if (isSingleStringLiteral(s) || NUM_LIT.test(s)) return true
  if (/^(true|false|null)$/i.test(s)) return true
  const p = s.indexOf('(')
  return p > 0 && IDENT.test(s.slice(0, p).trim()) && matchingParen(s, p) === s.length - 1
}

/**
 * 把任意文本安全地放进单引号字符串(注释/表注释用)。
 * ⚠️ 对应 Go 侧的 quoteString(internal/service/user_service.go)——那边连引号一起返回,
 *    这边只返回转义后的内容(前端拼接处自己带引号),规则相同:先 \\ 加倍,再 ' 加倍。
 *
 * ⚠️ 这类字段【不做白名单校验】:以前禁反斜杠,结果含 Windows 路径的注释直接把老表
 *    变成"不可编辑"(评审 r5 #5/#13、r6 #2)。正确做法是在拼接处转义。
 */
export function escapeSqlString(s: string): string {
  return (s ?? '').replace(/\\/g, '\\\\').replace(/'/g, "''")
}

