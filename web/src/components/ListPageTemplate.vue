<!-- 列表统一骨架：顶部查询/操作固定，表格占满剩余高度，底部分页始终可见。 -->
<template>
  <DetailPageTemplate class="list-page-template">
    <template v-if="$slots.header" #header><slot name="header" /></template>
    <slot />
    <template #footer>
      <div class="table-pager">
        <el-pagination background layout="total, sizes, prev, pager, next, jumper"
          :total="total" :current-page="page" :page-size="pageSize" :page-sizes="pageSizes"
          @current-change="emit('update:page', $event)" @size-change="changePageSize" />
      </div>
    </template>
  </DetailPageTemplate>
</template>

<script setup lang="ts">
import { watch } from 'vue'
import DetailPageTemplate from '@/components/DetailPageTemplate.vue'

const props = withDefaults(defineProps<{
  total: number
  page: number
  pageSize: number
  pageSizes?: number[]
}>(), { pageSizes: () => [10, 20, 50, 100] })
const emit = defineEmits<{
  (event: 'update:page', value: number): void
  (event: 'update:pageSize', value: number): void
}>()

function changePageSize(size: number) {
  emit('update:pageSize', size)
  emit('update:page', 1)
}

// 筛选、删除或刷新减少了总数时回到有效页，避免底部显示有数据而表格为空。
watch(() => [props.total, props.pageSize, props.page], () => {
  const maxPage = Math.max(1, Math.ceil(props.total / Math.max(1, props.pageSize)))
  const page = Math.min(Math.max(1, props.page), maxPage)
  if (page !== props.page) emit('update:page', page)
})
</script>

<style scoped>
.list-page-template {
  flex: 1;
  min-width: 0;
  width: 100%;
  overflow: hidden;
}
</style>
