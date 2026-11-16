<!--
  FilterRuleList.vue

  职责：可视化过滤规则列表：增删条件并校验字段、运算符和值。
  边界：编辑 WHERE 或 HAVING 条件数组；同一组件由两种语义复用，提示和空态文案可由 props 覆盖，不直接构造 SQL。
-->

<template>
  <div class="fr-wrap">
    <div class="fr-head">
      <span class="fr-hint">{{ hint || '操作符按字段类型自动切换;日期字段带时间快捷' }}</span>
      <el-button
        size="small"
        type="primary"
        plain
        :icon="Plus"
        :disabled="!hasTable"
        class="fr-add"
        @click="emit('add')"
      >
        添加条件
      </el-button>
    </div>

    <!--
      空态分两种原因,措辞必须区分:没选表时提示「没有过滤条件」会误导用户
      以为可以直接查,所以先判 hasTable;文案可由外部覆盖(HAVING 场景语义不同)。
    -->
    <div v-if="!where.length" class="fr-empty">
      {{ !hasTable ? '先选一张表' : (emptyText || '没有过滤条件(查询全表)') }}
    </div>

    <div v-for="(w, i) in where" :key="i" class="fr-row">
      <!-- 字段 -->
      <!--
        全部控件都用「:model-value + @update:model-value」而非 v-model:
        规则对象挂在父组件的查询配置上,子组件直接改 prop 会让重置/撤销
        失去唯一数据源,所以一律只读展示、改动走事件。
      -->
      <el-select
        :model-value="w.column"
        size="small"
        class="fr-col"
        placeholder="字段"
        @update:model-value="(v: string) => emit('set-column', i, v)"
      >
        <!-- ⚠️ key 必须带下标:别名重复是用户可达状态(useVisualQuery 会告警),只用别名会产生重复 key -->
        <el-option
          v-for="(f, fi) in fields"
          :key="`${f.alias}-${fi}`"
          :label="`${f.alias} (${f.sourceCol})`"
          :value="f.alias"
        />
      </el-select>

      <!-- 日期列:先给时间快捷 -->
      <!--
        日期列单独分支:选定预设后操作符与取值都由预设语义决定,
        不再暴露 op/值输入框 —— 否则会出现「预设是最近 7 天、操作符却写着 =」的自相矛盾规则。
      -->
      <template v-if="kindOf(indexCol(w), srcOf(w)) === 'date' && w.op !== 'distinct'">
        <el-select
          :model-value="w.preset || ''"
          size="small"
          class="fr-preset"
          placeholder="时间快捷"
          @update:model-value="(v: string) => emit('set-preset', i, v)"
        >
          <!-- 空串代表「手动」:切回它才会重新露出操作符与值输入框 -->
          <el-option label="(手动条件)" value="" />
          <el-option v-for="p in TIME_PRESETS" :key="p.key" :label="p.label" :value="p.key" />
        </el-select>
        <template v-if="!w.preset">
          <!-- 无预设(空串)→ 手动模式:操作符与值都交给用户填 -->
          <el-select
            :model-value="w.op"
            size="small"
            class="fr-op"
            @update:model-value="(v: string) => emit('set-op', i, v)"
          >
            <el-option v-for="o in opsFor(w)" :key="o.key" :label="o.label" :value="o.key" />
          </el-select>
          <!-- 无值操作符(如为空/非空)不渲染输入框,免得留下一个填了也被忽略的框 -->
          <el-input
            v-if="arityOf(w) !== 'none'"
            :model-value="w.value"
            size="small"
            class="fr-val"
            placeholder="值(如 2024-01-01 00:00:00)"
            @update:model-value="(v: string) => emit('set-value', i, v)"
          />
        </template>
        <template v-else-if="w.preset === 'custom'">
          <!--
            自定义区间:value 是开始(含)、value2 是结束(不含);
            左闭右开保证相邻区间首尾相接、既不重叠也不漏掉边界那一刻。
          -->
          <el-input
            :model-value="w.value"
            size="small"
            class="fr-val"
            placeholder="开始(含)"
            @update:model-value="(v: string) => emit('set-value', i, v)"
          />
          <el-input
            :model-value="w.value2"
            size="small"
            class="fr-val"
            placeholder="结束(不含)"
            @update:model-value="(v: string) => emit('set-value2', i, v)"
          />
        </template>
        <!-- 命名预设:区间边界已由预设定义,这里只解释语义,不再给输入框 -->
        <span v-else class="fr-preset-note">左闭右开区间</span>
      </template>

      <!-- 非日期列 -->
      <template v-else>
        <el-select
          :model-value="w.op"
          size="small"
          class="fr-op"
          @update:model-value="(v: string) => emit('set-op', i, v)"
        >
          <el-option v-for="o in opsFor(w)" :key="o.key" :label="o.label" :value="o.key" />
        </el-select>

        <template v-if="arityOf(w) === 'none'">
          <!-- 用一句说明占位而不是留白,否则用户会以为控件没渲染出来 -->
          <span class="fr-none-note">{{ w.op === 'distinct' ? '结果按字段去重；多个字段按组合值保留一条' : '(不需要值)' }}</span>
        </template>
        <template v-else-if="arityOf(w) === 'two'">
          <!--
            区间操作符(between)要两个界,用 ~ 拼成视觉上的一个区间;
            数值列的界换成 number 输入框,借浏览器原生校验挡掉明显非法的输入。
          -->
          <el-input
            :model-value="w.value"
            size="small"
            class="fr-val-s"
            :type="kindOf(indexCol(w), srcOf(w)) === 'number' ? 'number' : 'text'"
            placeholder="下界"
            @update:model-value="(v: string) => emit('set-value', i, v)"
          />
          <span class="fr-tilde">~</span>
          <el-input
            :model-value="w.value2"
            size="small"
            class="fr-val-s"
            :type="kindOf(indexCol(w), srcOf(w)) === 'number' ? 'number' : 'text'"
            placeholder="上界"
            @update:model-value="(v: string) => emit('set-value2', i, v)"
          />
        </template>
        <template v-else>
          <!--
            单值操作符的占位提示随算子变:list 要英文逗号分隔多值、
            like 要带 % 通配,填法完全不同,必须在占位里交代清楚。
          -->
          <el-input
            :model-value="w.value"
            size="small"
            class="fr-val"
            :type="kindOf(indexCol(w), srcOf(w)) === 'number' ? 'number' : 'text'"
            :placeholder="placeholderFor(w)"
            @update:model-value="(v: string) => emit('set-value', i, v)"
          />
        </template>
      </template>

      <el-button link size="small" type="danger" class="fr-del" @click="emit('remove', i)">
        <el-icon><Close /></el-icon>
      </el-button>

      <!--
        文本列用比较操作符时的提醒:字典序比较容易出意外结果
        (如 '9' > '10' 成立),必须说清,不能静静给个看着对的 SQL。
      -->
      <div v-if="showTextCmpNote(w)" class="fr-cmp-note">
        <el-icon><Warning /></el-icon>
        <span>{{ STRING_CMP_NOTE }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Close, Plus, Warning } from '@element-plus/icons-vue'
import {
  FILTER_OPS_BY_KIND,
  STRING_CMP_NOTE,
  TIME_PRESETS,
  isCmpOp,
  type ValueKind
} from '@/constants/queryBuilderMeta'
import type { QueryWorkflowFieldRule, QueryWorkflowWhere } from '@/types'

const props = defineProps<{
  where: QueryWorkflowWhere[]
  fields: QueryWorkflowFieldRule[]
  kindOf: (col: string, sourceIdx?: number) => ValueKind
  hasTable: boolean
  /**
   * 顶部提示语。不传用 WHERE 的默认文案。
   * ⚠️ 本组件被 WHERE 与 HAVING 两处复用,后者的语义完全不同
   *    (作用在 GROUP BY 之后),文案必须能覆盖。
   */
  hint?: string
  /** 空态文案;不传用 WHERE 的默认文案「没有过滤条件(查询全表)」 */
  emptyText?: string
}>()

const emit = defineEmits<{
  (e: 'add'): void
  (e: 'remove', i: number): void
  (e: 'set-column', i: number, v: string): void
  (e: 'set-op', i: number, v: string): void
  (e: 'set-preset', i: number, v: string): void
  (e: 'set-value', i: number, v: string): void
  (e: 'set-value2', i: number, v: string): void
}>()

/** where.column 存的是输出字段别名 → 找到源列 */
function indexCol(w: QueryWorkflowWhere): string {
  return props.fields.find((f) => f.alias === w.column)?.sourceCol ?? w.column
}

/**
 * 该条件引用的字段属于哪张源表。
 * ⚠️ 多表时同名列的类型可能不同(kindOf 是按表查的),必须带上 sourceIdx,
 *    否则过滤操作符会按错的类型给(如把数值列当字符串)。
 */
function srcOf(w: QueryWorkflowWhere): number {
  return props.fields.find((f) => f.alias === w.column)?.sourceIdx ?? 0
}

/**
 * 按字段类型取可用操作符。
 * 查不到类型时降级为字符串算子表:字段被改名/删掉后 kindOf 可能给不出类型,
 * 下拉留空会让整行没法编辑,不如给一套通用算子保证始终可用。
 */
function opsFor(w: QueryWorkflowWhere) {
  return FILTER_OPS_BY_KIND[props.kindOf(indexCol(w), srcOf(w))] ?? FILTER_OPS_BY_KIND.string
}

/** 文本列 + 比较操作符(> >= < <= between)→ 需要提示是字典序比较 */
function showTextCmpNote(w: QueryWorkflowWhere): boolean {
  return props.kindOf(indexCol(w), srcOf(w)) === 'string' && isCmpOp(w.op)
}

/**
 * 该操作符需要几个值。
 * 查不到时按 'one' 兜底(旧配置里可能留着已下线的算子)——
 * 宁可多给一个输入框,也好过整行一个可编辑控件都没有。
 */
function arityOf(w: QueryWorkflowWhere): 'none' | 'one' | 'two' | 'list' {
  const op = opsFor(w).find((o) => o.key === w.op)
  return op?.arity ?? 'one'
}

/** 值输入框的占位提示:list 与 like 的输入约定不同,必须分别交代 */
function placeholderFor(w: QueryWorkflowWhere) {
  const ar = arityOf(w)
  if (ar === 'list') return '多个值用英文逗号分隔,如 北京,上海'
  if (w.op === 'like' || w.op === 'not_like') return '支持 % 通配,如 %京%'
  return '值'
}
</script>

<style scoped>
.fr-wrap {
  margin-bottom: 14px;
}
.fr-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.fr-no {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--brand, #eab308);
  color: #1f2937;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  font-weight: 700;
  flex: 0 0 16px;
}
.fr-title {
  font-size: 14px;
  font-weight: 600;
}
.fr-count {
  font-size: 11px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  line-height: 15px;
}
.fr-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
/* 文本列比较操作符的提醒:占一整行,跟在条件行下面 */
.fr-cmp-note {
  flex: 1 0 100%;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  padding-left: 4px;
  font-size: 12px;
  color: var(--el-color-warning);
}
.fr-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.fr-add {
  margin-left: auto;
}
.fr-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  padding: 2px 0 4px;
}
.fr-col {
  width: 190px;
}
.fr-op {
  width: 116px;
}
.fr-preset {
  width: 126px;
}
.fr-val {
  flex: 1;
  min-width: 160px;
}
.fr-val-s {
  width: 110px;
}
.fr-tilde {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
.fr-none-note,
.fr-preset-note {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.fr-del {
  margin-left: auto;
}
</style>
