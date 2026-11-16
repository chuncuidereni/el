/**
 * filter.ts
 *
 * 职责：过滤条件工具：定义操作符，并判断分组、文本类型、条件数量及清理无效字段。
 * 边界：清理结果必须保留有效嵌套条件并丢弃空节点；操作符是否需要值由该表统一驱动。
 */

import type { FilterNode } from '@/types'
export type { FilterNode }

/** 操作符选项 */
export const OP_OPTIONS = [
  { value: 'eq', label: '=' },
  { value: 'ne', label: '≠' },
  { value: 'gt', label: '>' },
  { value: 'gte', label: '≥' },
  { value: 'lt', label: '<' },
  { value: 'lte', label: '≤' },
  { value: 'like', label: '包含' },
  { value: 'nlike', label: '不包含' },
  { value: 'starts', label: '开头是' },
  { value: 'ends', label: '结尾是' },
  { value: 'in', label: '在列表中 IN' },
  { value: 'notin', label: '不在列表中 NOT IN' },
  { value: 'between', label: '在范围内 BETWEEN' },
  { value: 'isnull', label: '为空' },
  { value: 'isnotnull', label: '不为空' },
  { value: 'distinct', label: '字段去重' },
]

// 需要填值的操作符集合:isnull/isnotnull 只看列名,不该出现值输入框
const VALUE_OPS = new Set([
  'eq', 'ne', 'gt', 'gte', 'lt', 'lte',
  'like', 'nlike', 'starts', 'ends', 'in', 'notin', 'between',
])

/** 该操作符是否需要值输入框 */
export function needsValue(op: string): boolean {
  return VALUE_OPS.has(op)
}

/** 值输入框占位提示 */
export function valuePlaceholder(op: string): string {
  // 提示文案即实际分隔符:后端按同一个分隔符切分,改这里要同步改后端解析
  if (op === 'in' || op === 'notin') return '多个值,逗号分隔'
  if (op === 'between') return '最小值, 最大值'
  return '输入值'
}

/** 是否为分组节点 */
export function isGroupNode(node: FilterNode): boolean {
  return Array.isArray(node.children)
}

/** 是否为文本类列(可用于 LIKE 系列操作符) */
export function isTextType(type: string): boolean {
  const t = type.toLowerCase()
  // 按类型名子串判断而不是精确枚举:Nullable(String)、LowCardinality(String)、
  // Array(String) 这类包装类型也要算文本;IPv4/Enum 在 CH 里本身就按字符串比较
  return (
    t.includes('string') ||
    t.includes('fixedstring') ||
    t.includes('uuid') ||
    t.includes('ipv') ||
    t.includes('enum')
  )
}

/** 统计组内叶子条件数量 */
export function countConditions(node: FilterNode): number {
  if (isGroupNode(node)) {
    // 分组只做汇总,不带自己的条件
    return (node.children ?? []).reduce((sum, c) => sum + countConditions(c), 0)
  }
  // 有列名才算一个有效条件:空行(用户点了「添加条件」还没填)不计入
  return node.column ? 1 : 0
}

/** 清理过滤树中引用已不存在列的条件(表结构变化/持久化恢复时) */
export function sanitizeFilterColumns(node: FilterNode, validCols: Set<string>): FilterNode | null {
  if (isGroupNode(node)) {
    const children = (node.children ?? [])
      .map((c) => sanitizeFilterColumns(c, validCols))
      // filter(Boolean) 掉整枝被删空的 null
      .filter(Boolean) as FilterNode[]
    // 组内条件全没了,这个组也没有存在意义 → 返回 null 交给上层继续剔除
    if (!children.length) return null
    return { logic: node.logic ?? 'and', children }
  }
  if (!node.column || !validCols.has(node.column)) return null
  return node
}

/** 清理过滤树:去掉空条件/空分组,返回干净的可提交结构;整树为空返回 null */
export function cleanFilter(node: FilterNode): FilterNode | null {
  if (isGroupNode(node)) {
    const children = (node.children ?? []).map(cleanFilter).filter(Boolean) as FilterNode[]
    if (!children.length) return null
    // 保留原 logic(and/or),否则用户选的「或者」会被悄悄改成「并且」
    return { logic: node.logic ?? 'and', children }
  }
  const col = (node.column ?? '').trim()
  if (!col) return null
  if (needsValue(node.op ?? '')) {
    // 值统一转成字符串并去首尾空白:输入框可能给 number,后端按字符串比对
    const v = (node.value ?? '').toString().trim()
    // 只填了列没填值 = 半成品条件,提交上去会被当成空串匹配,直接丢弃
    if (!v) return null
    return { column: col, op: node.op, value: v }
  }
  // isnull / isnotnull 这类不带值,多余的值字段一并去掉
  return { column: col, op: node.op }
}
