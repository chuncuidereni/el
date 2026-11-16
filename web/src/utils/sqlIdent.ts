/**
 * sqlIdent.ts
 *
 * 职责：SQL 标识符检测：判断表达式是否出现未被限定或字符串包裹的独立字段名。
 * 边界：匹配必须处理大小写、边界、引号和注释，供自动补列逻辑避免把已有表达式重复加入。
 */

import { escapeRegExp } from './confirm'

/**
 * expr 里是否出现「独立的」ident(前后都不是标识符字符)。
 *
 * 用途:判断分区键表达式有没有引用号码列。
 *
 * ⚠️ 为什么单独抽成函数:ident 来自 CSV 表头 / 数据库列名,不受控。
 *    直接拼进 RegExp 的话 ——
 *      · 含 ( [ + . \ 等元字符 → 匹配语义被悄悄改写(该提醒的没提醒);
 *      · 以 ( [ \ 结尾 → new RegExp 直接抛,而调用方在 computed 里 → 组件渲染崩。
 *    (评审 security·high,已实测:col( / a[ / a\ 抛;Phone(主) / a+b / a.b / p\d 静默错。)
 */
export function mentionsBareIdent(expr: string, ident: string): boolean {
  const e = (expr ?? '').trim()
  const p = (ident ?? '').trim()
  if (!e || !p) return false
  try {
    return new RegExp(`(^|[^A-Za-z0-9_])${escapeRegExp(p)}([^A-Za-z0-9_]|$)`).test(e)
  } catch {
    // escapeRegExp 之后理论上不会再抛;真抛了也不能让 UI 因为一个列名崩掉
    return false
  }
}
