<!--
  GroupFilterBar.vue

  职责：分组筛选栏：切换分组、查询和批量操作状态。
  边界：接收分组和选中项并发出选择、增删分组事件；只组织筛选栏交互，持久化和数据刷新由父页面负责。
-->

<template>
  <!--
    通用「分组筛选条」= 一个下拉(切换筛选)+ 一个 popover(管理分组)。

    数据源 / 标准源表（ods_data） / 目标库表（dwd_data） / 数据探索四个列表页共用它,所以文案与哨兵值全部由 props 传入:
    各页的「默认分组」含义并不相同(有的是 default 分组,有的是 ungrouped = 没分组的表),
    组件不解释取值含义,只负责展示和把用户动作上报出去。

    泛型 T 只要求 { id, name } + 可选的 count / tables:这样各个页面的分组类型
    (TableGroup、数据源分组、探索分组)都能直接传进来,不必先转成统一类型。
  -->
  <div class="gfb">
    <!-- 分组筛选:紧凑下拉,不占独立侧栏 -->
    <el-select
      :model-value="filter"
      class="gfb-sel"
      @update:model-value="(v: string) => emit('update:filter', v)"
    >
      <template #prefix><el-icon><Folder /></el-icon></template>
      <!-- 全部/默认分组是哨兵项,与自定义分组并列展示以便一眼比较数量 -->
      <!-- 数量拼进 label 而不是单独渲染:选中后收起状态的显示文字里也要带数量 -->
      <el-option value="all" :label="`${allLabel} · ${allCount}`" />
      <el-option :value="specialValue" :label="`${specialLabel} · ${specialCount}`" />
      <el-option
        v-for="g in groups"
        :key="g.id"
        :value="valueOf(g)"
        :label="`${g.name} · ${countOf(g)}`"
      />
    </el-select>

    <!-- 分组管理:增删改收进 popover -->
    <el-popover :width="248" trigger="click" placement="bottom-end">
      <template #reference>
        <el-button :icon="Setting" title="分组管理" class="gfb-manage" />
      </template>
      <div class="gfb-pop">
        <!-- popover 内重复一份列表:下拉只负责切换,这里兼做管理入口(改/删/新建) -->
        <div class="gfb-pop-head">分组管理</div>

        <!-- active 直接比字符串:筛选值即下拉的 value,'all'/特殊值/前缀id 都能命中 -->
        <div class="gfb-item" :class="{ active: filter === 'all' }" @click="emit('update:filter', 'all')">
          <span class="gfb-name">{{ allLabel }}</span>
          <span class="gfb-count">{{ allCount }}</span>
        </div>
        <div
          class="gfb-item"
          :class="{ active: filter === specialValue }"
          @click="emit('update:filter', specialValue)"
        >
          <span class="gfb-name">{{ specialLabel }}</span>
          <span class="gfb-count">{{ specialCount }}</span>
        </div>

        <div
          v-for="g in groups"
          :key="g.id"
          class="gfb-item"
          :class="{ active: filter === valueOf(g) }"
          @click="emit('update:filter', valueOf(g))"
        >
          <span class="gfb-name" :title="g.name">{{ g.name }}</span>
          <span class="gfb-count">{{ countOf(g) }}</span>
          <!-- 操作按钮必须 stop:否则点击会冒泡到行上的切换筛选 -->
          <span class="gfb-ops">
            <el-button link size="small" type="primary" :icon="EditPen" @click.stop="emit('edit', g)" />
            <el-button link size="small" type="danger" :icon="Delete" @click.stop="emit('remove', g)" />
          </span>
        </div>

        <!-- 无自定义分组时给一行占位,避免 popover 只剩头部和新建按钮显得残缺 -->
        <div v-if="!groups.length" class="gfb-empty">暂无自定义分组</div>

        <div class="gfb-foot">
          <el-button size="small" text type="primary" :icon="FolderAdd" @click="emit('new')">
            新建分组
          </el-button>
        </div>
      </div>
    </el-popover>
  </div>
</template>

<script setup lang="ts" generic="T extends { id: number; name: string; count?: number; tables?: string[] }">
import { Delete, EditPen, Folder, FolderAdd, Setting } from '@element-plus/icons-vue'

const props = withDefaults(
  defineProps<{
    /** 当前选中值:'all' | specialValue | <前缀><id> */
    filter: string
    /** 「全部」的展示名与数量(数量由调用方算好:每个页面的"全部"口径不同) */
    allLabel: string
    allCount: number
    /** 「默认分组」的展示名、选中值与数量 */
    specialLabel: string
    specialValue: string
    specialCount: number
    groups: T[]
    /** 分组 id 的选中值前缀(如 'g:');空则直接用 id 字符串 */
    groupPrefix?: string
  }>(),
  // 默认空前缀:不带前缀时选中值就是纯 id(数据源 / 探索页如此),带前缀的页面显式传 'g:'
  { groupPrefix: '' },
)

const emit = defineEmits<{
  (e: 'update:filter', v: string): void
  (e: 'new'): void
  (e: 'edit', g: T): void
  (e: 'remove', g: T): void
}>()

// 加前缀是为了让分组 id 不与 'all'/特殊值这类哨兵字符串撞车(空前缀则退回纯 id)
function valueOf(g: T): string {
  return props.groupPrefix + String(g.id)
}
// 列表只给 tables 不带 count 时按表数兜底,两种数据来源共用同一套展示逻辑
function countOf(g: T): number {
  return g.count ?? g.tables?.length ?? 0
}
</script>

<style scoped>
.gfb {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 固定宽度:各分组名长短不一,宽度由内容撑开的话,切换筛选时右侧按钮会左右跳动 */
.gfb-sel {
  width: 178px;
}
.gfb-manage {
  width: 32px;
  padding: 0;
}
/* 分组管理 popover
   触发按钮只有图标(见模板里的 :icon="Setting"),不带文字 —— 管理分组是低频操作,
   不能跟同行的「新建 / 批量删除」等主按钮抢位置。 */
.gfb-pop-head {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 4px;
}
.gfb-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  border-radius: 5px;
  cursor: pointer;
  font-size: 13.5px;
}
.gfb-item:hover {
  background: var(--el-fill-color);
}
.gfb-item.active {
  background: rgba(234, 179, 8, 0.12);
  color: var(--brand-strong, #b45309);
  font-weight: 600;
}
.gfb-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gfb-count {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
/* 操作按钮平时隐藏,悬停该行才出现:列表项是"点哪都切换筛选",常驻按钮容易误点(改/删分组) */
.gfb-ops {
  display: none;
  gap: 0;
}
.gfb-item:hover .gfb-ops {
  display: inline-flex;
}
.gfb-ops :deep(.el-button) {
  width: 32px;
  padding: 0;
}
.gfb-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  padding: 6px;
  text-align: center;
}
.gfb-foot {
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 4px;
  padding-top: 4px;
  text-align: center;
}
</style>
