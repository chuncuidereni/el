import type { DataSourceDetail } from '@/types'

export interface FileSourceColumn {
  name: string
  type: string
  comment: string
}

/**
 * 字段名可被 names 重命名，默认注释始终取原始文件表头。
 * 新接口直接返回对应关系；旧接口仅在列数一致时按原始列位置配对，避免注释串列。
 * 无表头文件没有原始列名，不把 column0 等自动编号当作业务说明。
 */
export function fileSourceColumns(detail: DataSourceDetail): FileSourceColumn[] {
  const columns = detail.columns ?? detail.rawColumns ?? []
  const raw = detail.rawColumns ?? []
  const hasHeader = detail.type === 'json' || detail.hasHeader !== false
  return columns.map((column, index) => ({
    name: column.name,
    type: column.type,
    comment: hasHeader
      ? detail.columnComments?.[column.name]
        ?? (raw.length === columns.length ? raw[index]?.name : undefined)
        ?? (detail.names?.length ? '' : column.name)
      : '',
  }))
}
