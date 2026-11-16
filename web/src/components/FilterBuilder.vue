<!--
  FilterBuilder.vue

  职责：过滤条件构建器：维护字段、运算符和值并生成结构化条件。
  边界：递归编辑 FilterNode，以 update:modelValue 回写父级，并通过 run/remove 通知执行或删除；本组件不直接请求后端。
-->

<template>
  <div class="fb-group">
    <!-- 组头:组合逻辑 + 子组标识 -->
    <div class="fb-head">
      <!-- 根组不可删除,逻辑选择器也加宽以示层级差异 -->
      <el-select v-model="group.logic" class="fb-logic" :class="{ 'fb-logic-root': isRoot }">
        <el-option label="全部满足" value="and" />
        <el-option label="满足任一" value="or" />
      </el-select>
      <span v-if="!isRoot" class="fb-group-tag">条件组</span>
      <span class="fb-spacer" />
      <el-button v-if="!isRoot" link type="danger" :icon="Delete" @click="emit('remove')" />
    </div>

    <!-- 条件列表:一个条件一行,分组嵌套缩进 -->
    <div class="fb-items">
      <div v-for="(item, i) in group.children" :key="i" class="fb-item">
        <template v-if="isGroupNode(item)">
          <!-- 组节点递归渲染:子组用同一组件,变更/删除/回车执行都透传到根节点处理 -->
          <FilterBuilder
            :model-value="item"
            :all-columns="allColumns"
            :col-comments="colComments"
            :col-types="colTypes"
            @update:model-value="emitChange"
            @remove="removeAt(i)"
            @run="emit('run')"
          />
        </template>
        <div v-else class="fb-cond">
          <!-- 字段下拉带注释列:选字段时顺带确认口径,不必去查表结构 -->
          <el-select v-model="item.column" placeholder="字段" filterable clearable class="f-col" @change="onColChange(item)">
            <el-option v-for="c in allColumns" :key="c" :label="c" :value="c">
              <span class="opt-name">{{ c }}</span>
              <span v-if="colComments[c]" class="opt-comment">{{ colComments[c] }}</span>
            </el-option>
          </el-select>
          <!-- 操作符随字段类型变化:换字段后旧操作符若不再合法,由 onColChange 重置 -->
          <el-select v-model="item.op" class="f-op">
            <el-option v-for="o in optsFor(item)" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <!-- isnull/notnull 之类不需要右值,隐藏输入框避免提交无意义参数 -->
          <el-input
            v-if="needsValue(item.op ?? '')"
            v-model="item.value"
            :placeholder="valuePlaceholder(item.op ?? '')"
            class="f-val"
            clearable
            @keyup.enter="emit('run')"
          />
          <span v-if="item.op === 'distinct'" class="fb-dedup-note">筛选后按字段去重；多个字段按组合值保留一条</span>
          <el-button link type="danger" :icon="Delete" @click="removeAt(i)" />
        </div>
      </div>
      <!-- 空组也要占位提示,否则只剩组头和添加按钮,用户不知道缺什么 -->
      <div v-if="!group.children?.length" class="fb-empty">暂无条件</div>
    </div>

    <!-- 添加按钮 -->
    <div class="fb-actions">
      <el-button size="small" text :icon="Plus" @click="addCond">添加条件</el-button>
      <el-button size="small" text :icon="FolderAdd" @click="addGroup">添加分组</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Delete, FolderAdd, Plus } from '@element-plus/icons-vue'
import { isGroupNode, isTextType, needsValue, valuePlaceholder, OP_OPTIONS, type FilterNode } from '@/utils/filter'

defineOptions({ name: 'FilterBuilder' })

const props = defineProps<{
  modelValue: FilterNode
  allColumns: string[]
  colComments: Record<string, string>
  colTypes: Record<string, string>
  isRoot?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: FilterNode): void
  (e: 'remove'): void
  (e: 'run'): void
}>()

// 不拷贝直接引用父级节点:编辑就地在原对象上生效,父组件无需重建整棵树
const group = computed(() => props.modelValue)

/** 按列类型过滤操作符:非文本列不显示 LIKE 系列(避免 uid LIKE %1% 这类无效查询) */
function optsFor(item: FilterNode) {
  const col = item.column ?? ''
  // 未选字段时无法判定类型,先给全量操作符,选完字段再由 onColChange 收敛
  if (!col) return OP_OPTIONS
  const type = props.colTypes[col] ?? ''
  if (isTextType(type)) return OP_OPTIONS
  return OP_OPTIONS.filter((o) => !['like', 'nlike', 'starts', 'ends'].includes(o.value))
}

function onColChange(item: FilterNode) {
  // 切换字段后,若当前操作符不再适用则重置为 =
  if (!optsFor(item).some((o) => o.value === item.op)) {
    item.op = 'eq'
  }
}

// 叶子节点可能没有 children 字段,增删前按需补齐,避免对 undefined 调 push/splice
function ensureChildren(): FilterNode[] {
  if (!Array.isArray(group.value.children)) group.value.children = []
  return group.value.children
}

// 改动是就地生效的,emit 只是通知父级"树变了",以便父级刷新生成的 SQL / 脏标记
function emitChange() {
  emit('update:modelValue', group.value)
}

function addCond() {
  // 新条件默认 eq 且字段为空,渲染后由用户挑选;空字段在生成 SQL 时会被过滤
  ensureChildren().push({ logic: 'and', column: '', op: 'eq', value: '' })
  emitChange()
}

function addGroup() {
  // 子组默认 and,空 children 由模板的"暂无条件"占位承接
  ensureChildren().push({ logic: 'and', children: [] })
  emitChange()
}

function removeAt(i: number) {
  // 按索引删除:列表用 index 作为 key,删除后重排即触发重新渲染
  ensureChildren().splice(i, 1)
  emitChange()
}
</script>

<style scoped>
.fb-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.fb-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.fb-logic {
  width: 108px;
}
.fb-group-tag {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.fb-spacer {
  flex: 1;
}
.fb-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 8px 0 6px;
  padding-left: 12px;
  border-left: 1px dashed var(--el-border-color);
}
.fb-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fb-cond {
  display: flex;
  align-items: center;
  gap: 6px;
}
.fb-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  padding: 2px 0;
}
.fb-actions {
  display: flex;
  gap: 2px;
}
.f-col {
  width: 150px;
}
.f-op {
  width: 130px;
}
.f-val {
  width: 200px;
}
.fb-dedup-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
