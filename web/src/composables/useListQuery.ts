/**
 * useListQuery.ts
 *
 * 职责：列表查询状态组合式函数：维护关键词、分组和分页，并提供搜索、重置和删除分组操作。
 * 边界：状态按每次调用独立创建；reset 清空关键词并回到全部分组，删除当前分组时回退到 all，不直接请求后端。
 */

import { ref } from 'vue'

/** 列表查询统一使用待提交条件；只有点击查询或重置才更新结果。 */
export function useListQuery(initialGroup = 'all') {
  const draftGroup = ref(initialGroup)
  const draftKeyword = ref('')
  const group = ref(initialGroup)
  const keyword = ref('')

  /** 将草稿条件提交为生效条件；关键词统一去首尾空白。 */
function search() {
    group.value = draftGroup.value
    keyword.value = draftKeyword.value.trim()
  }

  /** 清空草稿并立即恢复初始分组，使界面与查询结果保持一致。 */
function reset() {
    draftGroup.value = initialGroup
    draftKeyword.value = ''
    search()
  }

  /** 当前分组被删除时回退到初始分组，同时修复草稿和已生效状态。 */
function removeGroup(value: string) {
    if (draftGroup.value === value) draftGroup.value = initialGroup
    if (group.value === value) group.value = initialGroup
  }

  return { draftGroup, draftKeyword, group, keyword, search, reset, removeGroup }
}
