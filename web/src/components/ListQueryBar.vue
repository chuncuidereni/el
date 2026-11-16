<!--
  ListQueryBar.vue

  职责：列表查询栏：同步搜索、分组、刷新和批量选择操作。
  边界：同步关键词、分组和批量选择，并发出 search/reset/refresh/remove 等命令；查询请求和结果状态由调用方执行。
-->

<template>
  <div class="list-query-bar">
    <div class="list-query-conditions">
      <span v-if="groups" class="list-query-label">分组</span>
      <GroupFilterBar
        v-if="groups"
        :filter="group ?? 'all'"
        :all-label="allLabel ?? '全部'"
        :all-count="allCount ?? 0"
        :special-label="specialLabel ?? '默认分组'"
        :special-value="specialValue ?? 'default'"
        :special-count="specialCount ?? 0"
        :groups="groups"
        :group-prefix="groupPrefix"
        @update:filter="(value: string) => emit('update:group', value)"
        @new="emit('new')"
        @edit="(value) => emit('edit', value)"
        @remove="(value) => emit('remove', value)"
      />
      <span class="list-query-label">关键词</span>
      <el-input
        :model-value="keyword"
        clearable
        class="list-query-keyword"
        :placeholder="keywordPlaceholder"
        @update:model-value="(value: string) => emit('update:keyword', value)"
        @keyup.enter="emit('search')"
      />
      <slot name="extra" />
    </div>
    <div class="list-query-actions">
      <el-button type="primary" :icon="Search" @click="emit('search')">查询</el-button>
      <el-button :icon="RefreshLeft" @click="emit('reset')">重置</el-button>
    </div>
  </div>
</template>

<script setup lang="ts" generic="T extends { id: number; name: string; count?: number; tables?: string[] }">
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import GroupFilterBar from '@/components/GroupFilterBar.vue'

/**
 * 列表查询栏的展示契约。
 * group/keyword 采用父组件受控值；groups 只用于渲染分组，不在子组件内修改源数组。
 */
withDefaults(defineProps<{
  group?: string
  keyword: string
  allLabel?: string
  allCount?: number
  specialLabel?: string
  specialValue?: string
  specialCount?: number
  groups?: T[]
  groupPrefix?: string
  keywordPlaceholder?: string
}>(), { groupPrefix: '', keywordPlaceholder: '搜索名称' })

/** 所有筛选与分组编辑均通过事件交还父组件，子组件不直接发请求或写共享状态。 */
const emit = defineEmits<{
  (event: 'update:group', value: string): void
  (event: 'update:keyword', value: string): void
  (event: 'search'): void
  (event: 'reset'): void
  (event: 'new'): void
  (event: 'edit', value: T): void
  (event: 'remove', value: T): void
}>()
</script>

<style scoped>
.list-query-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: none;
  padding: 8px 0;
}
.list-query-conditions,
.list-query-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.list-query-conditions {
  flex-wrap: wrap;
  min-width: 0;
}
.list-query-actions {
  flex: none;
  margin-left: auto;
}
.list-query-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  white-space: nowrap;
}
.list-query-keyword {
  width: 240px;
}
@media (max-width: 900px) {
  .list-query-bar { flex-wrap: wrap; }
}
</style>
