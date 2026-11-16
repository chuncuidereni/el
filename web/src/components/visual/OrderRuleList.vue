<!--
  OrderRuleList.vue

  职责：可视化排序规则列表：维护排序字段、方向及顺序。
  边界：维护排序字段、方向和顺序并发出增删改事件；可新增条件取决于尚未排序的输出字段，无表空态由父级页签兜底。
-->

<template>
  <!--
    ORDER BY 规则列表(可视化构建器的「④ 排序」页签)。

    纯受控组件:自己不持有规则数据,增删改只 emit 事件,由父级改查询配置后回灌 props ——
    这样「重置 / 撤销 / 保存」都只需操作父级那一份配置,不必在这里做状态同步。
    另注:排序除了决定结果顺序,还决定「按列去重(LIMIT 1 BY)时留下哪一条」,所以它不只是展示层的事。
  -->
  <div class="or-wrap">
    <div class="or-head">
      <span class="or-hint">序号即优先级,靠前的先排</span>
      <el-button
        size="small"
        type="primary"
        plain
        :icon="Plus"
        :disabled="!canAdd"
        class="or-add"
        @click="emit('add')"
      >
        添加排序
      </el-button>
    </div>
    <!--
      空态有两种成因,提示的下一步动作不同:还没建输出字段(先去加字段)
      vs 有字段但没排过序(此时不排序也是合法选择)。
    -->
    <div v-if="!orderBy.length" class="or-empty">
      {{ fields.length ? '默认顺序(不排序)' : '先添加输出字段' }}
    </div>
    <!--
      行顺序即优先级:数组下标直接映射 ORDER BY 中字段的先后。
      key 用下标而非列名:规则本身不允许重复列(见 canAdd),下标在这里是唯一且与优先级一致的标识;
      删除中间一条时后续行会整体上移,靠的就是"顺序即语义"这一点。
    -->
    <div v-for="(o, i) in orderBy" :key="i" class="or-row">
      <!-- 展示用 1 起序号(下标从 0 开始);删掉中间一条,后面的序号会自动上移 -->
      <span class="or-idx">{{ i + 1 }}</span>
      <!--
        :model-value + @update:model-value 而非 v-model:规则对象挂在父组件的查询配置上,
        子组件直接改 prop 会被 Vue 警告,也绕过了父级(父级要负责重置/去重/推导 SQL)。
      -->
      <el-select
        :model-value="o.column"
        size="small"
        class="or-col"
        @update:model-value="(v: string) => emit('set-column', i, v)"
      >
        <!-- ⚠️ key 必须带下标:别名重复是用户可达状态,只用别名会产生重复 key -->
        <el-option v-for="(f, fi) in fields" :key="`${f.alias}-${fi}`" :label="f.alias" :value="f.alias" />
      </el-select>
      <!--
        方向在模板层换算成 'asc'/'desc' 两个字符串:数据里只存布尔 desc,
        避免同一语义在配置里出现两种表达;回传时再折算回布尔。
      -->
      <el-select
        :model-value="o.desc ? 'desc' : 'asc'"
        size="small"
        class="or-dir"
        @update:model-value="(v: string) => emit('set-desc', i, v === 'desc')"
      >
        <el-option label="升序 ↑" value="asc" />
        <el-option label="降序 ↓" value="desc" />
      </el-select>
      <el-button link size="small" type="danger" class="or-del" @click="emit('remove', i)">
        <el-icon><Close /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Close, Plus } from '@element-plus/icons-vue'
import type { QueryWorkflowFieldRule, QueryWorkflowOrder } from '@/types'

const props = defineProps<{
  orderBy: QueryWorkflowOrder[]
  fields: QueryWorkflowFieldRule[]
  /**
   * ⚠️ 父级(VisualQueryBuilder 的排序页签)会传这个 prop,但本组件目前没用到它:
   *    可否添加只看有没有「未排过序的输出字段」(见 canAdd)。
   *    改空态文案时别误以为它在生效 —— 没选表的情况由上层页签兜住。
   */
  hasTable: boolean
}>()

const emit = defineEmits<{
  (e: 'add'): void
  (e: 'remove', i: number): void
  (e: 'set-column', i: number, v: string): void
  (e: 'set-desc', i: number, v: boolean): void
}>()

/**
 * 同一字段排两次没有意义,所以仅当还有「没出现在 orderBy 里的输出字段」时才可添加。
 * 提前禁用按钮,好过让用户点出一条必然重复的规则再自己发现。
 */
const canAdd = computed(() => props.fields.some((f) => !props.orderBy.some((o) => o.column === f.alias)))
</script>

<style scoped>
/* 排序区没有独立的标题行:编号/标题/计数由外层页签(④ 排序)渲染,这里只画"提示 + 添加按钮"那一行 */
.or-wrap {
  margin-bottom: 14px;
}
.or-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
/* ⚠️ .or-no / .or-title / .or-count 三个类当前模板未使用,是从 FilterRuleList 同构复制过来的遗留声明;
   保留它们不影响渲染,但改样式时不要以为它们在这一版界面上生效 */
.or-no {
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
.or-title {
  font-size: 14px;
  font-weight: 600;
}
.or-count {
  font-size: 11px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  line-height: 15px;
}
.or-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.or-add {
  margin-left: auto;
}
.or-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  padding: 2px 0 4px;
}
.or-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.or-idx {
  width: 16px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.or-col {
  width: 190px;
}
.or-dir {
  width: 108px;
}
.or-del {
  margin-left: auto;
}
</style>
