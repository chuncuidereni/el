/**
 * confirm.ts
 *
 * 职责：确认交互工具：统一 Element Plus 二次确认并转成正反布尔结果。
 * 边界：确认框关闭或取消统一返回 false；动态文案中的用户输入必须先转义正则元字符。
 */

import { ElMessageBox } from 'element-plus'

// SQL 标识符匹配也复用此函数,其中的正则元字符必须转义。
export function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 普通写操作(新增 / 修改 / 启停 / 移动分组等)的二次确认。
 * 删除、清空、覆盖这类不可恢复操作请用 {@link confirmDestructive}。
 */
export function confirmAction(title: string, message: string, confirmText = '确认'): Promise<boolean> {
  return ElMessageBox.confirm(message, title, {
    type: 'warning',
    confirmButtonText: confirmText,
    cancelButtonText: '取消',
    closeOnClickModal: false,
    closeOnPressEscape: false,
  })
    .then(() => true)
    .catch(() => false)
}

/**
 * 删除和清空等操作只需点击确认;取消/关闭返回 false。
 * name 用于展示操作对象,extra 为可选附加提示。
 */
export function confirmDestructive(title: string, name: string, confirmText: string, extra = ''): Promise<boolean> {
  return ElMessageBox.confirm(
    `该操作不可恢复。确认${confirmText}「${name}」吗？${extra}`,
    title,
    {
      type: 'warning',
      confirmButtonText: `确认${confirmText}`,
      confirmButtonClass: 'el-button--danger',
      cancelButtonText: '取消',
      closeOnClickModal: false,
      closeOnPressEscape: false,
    },
  )
    .then(() => true)
    .catch(() => false)
}
