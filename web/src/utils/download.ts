/**
 * download.ts
 *
 * 职责：浏览器下载与 CSV 编码工具：创建 Blob 链接、清理对象 URL，并处理 BOM 和单元格引号。
 * 边界：文件名只影响下载属性；CSV 单元格必须按 RFC 风格转义引号、逗号和换行，下载后释放临时 URL。
 * DataEditor.vue 与 SqlQueryView.vue 仍有等价实现；新代码统一使用本模块，旧页面在后续改动时迁移。
 */

/** 触发浏览器下载。 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  // ⚠️ 必须释放:不 revoke 的话 blob 会一直挂在文档上(导出大文件时内存只涨不降)
  URL.revokeObjectURL(url)
}

/** CSV 的 UTF-8 BOM —— 不带它 Excel 打开中文会乱码。 */
export const CSV_BOM = '\ufeff'

/** CSV 单元格转义:含分隔符 / 引号 / 换行时加引号并转义内部引号。 */
export function csvCell(v: string): string {
  return /[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v
}

/** 拼一行 CSV。 */
export function csvRow(cells: string[]): string {
  return cells.map(csvCell).join(',')
}
