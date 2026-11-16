<!--
  ExploreWizard.vue

  职责：可视化探索向导：分步选择源表、字段、过滤和排序并生成查询配置。
  边界：从 connId/database 分步拉取表和字段，并回填 existingSources/existingFields；生成配置时合并已有连接和字段，旧异步响应不得覆盖当前步骤。
-->

<template>
  <!-- 表数据预览:配置关联键时先看一眼真实数据 -->
  <el-dialog
    v-model="pvVisible"
    :title="`预览前 10 行 · ${pvTitle}`"
    width="min(1100px, 94vw)"
    top="6vh"
    append-to-body
  >
    <div v-loading="pvLoading" class="wz-pv-wrap">
      <el-table v-if="pvColumns.length" :data="pvRows" size="small" border max-height="58vh">
        <el-table-column v-for="(c, i) in pvColumns" :key="i" :label="c" min-width="150">
          <template #default="{ row }">
            <span class="mono wz-pv-cell">{{ row[i] ?? '' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-else-if="!pvLoading" class="wz-pv-empty">这张表没有数据(或没有读权限)</div>
    </div>
  </el-dialog>

  <el-dialog
    :model-value="modelValue"
    title="探索向导"
    width="760px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <!-- ── 第 1 步:选表(多选) ── -->
    <div v-if="step === 1">
      <p class="wz-step-hint">
        勾选本次查询要用的表(第一张将作为<b>主表</b>,后续可在关系图中调整)。
        <span class="muted">单表会跳过关联步骤;最多 {{ maxTables }} 张。</span>
      </p>
      <el-input v-model="kw" size="small" clearable placeholder="搜索表名 / 注释" class="wz-kw">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <div v-loading="loading" class="wz-table-list">
        <!-- 搜索只收窄显示:被过滤掉的已勾选表仍留在 picked 里,不会敲几个字就丢掉选择 -->
        <el-checkbox-group v-model="picked">
          <div v-for="t in filtered" :key="t.name" class="wz-table-item">
            <el-checkbox :value="t.name">
              <span class="mono">{{ t.name }}</span>
              <span v-if="t.remark" class="wz-remark" :title="t.remark">{{ t.remark }}</span>
            </el-checkbox>
          </div>
          <el-empty v-if="!loading && !filtered.length" description="没有匹配的表" :image-size="48" />
        </el-checkbox-group>
      </div>
    </div>

    <!-- ── 第 2 步:关系图 —— 每张表选关联键,线自动连,可追加表 ── -->
    <div v-else-if="step === 2">
      <p class="wz-step-hint">
        为每张表选择<b>关联键</b>(通常是手机号),选好即连线。键格式不一致时点连线上的
        <b>⚙</b> 配置归一化处理(如明文 mdn → MD5)。
      </p>

      <!-- 「下一步」被禁用时必须说清原因,否则点了没反应像界面坏了 -->
      <el-alert
        v-if="missingKeyTables.length"
        type="warning"
        :closable="false"
        show-icon
        class="wz-alert"
      >
        <template #title>
          还有 {{ missingKeyTables.length }} 张表未选关联键:
          <b class="mono">{{ missingKeyTables.join('、') }}</b>
          —— 每张表都要选一列做关联,否则无法下一步。
        </template>
      </el-alert>

      <!-- 主表节点在最左,关联表向右依次排开 -->
      <!-- 左:主表(垂直居中于右侧行区);右:每张关联表一行「连线+节点」,互不堆积 -->
      <div class="wz-graph">
        <div v-if="pickedSources[0]" class="wz-graph-left">
          <div class="wz-node wz-node-main">
            <div class="wz-node-head">
              <el-tag size="small" type="warning" effect="plain">主表</el-tag>
              <span class="mono wz-node-name" :title="pickedSources[0].name">{{ pickedSources[0].name }}</span>
              <el-button size="small" link type="primary" title="看这张表的前 10 行"
                @click="previewTable(pickedSources[0].name)">预览</el-button>
            </div>
            <div class="wz-node-row">
              <span class="wz-node-label">关联键</span>
              <el-select
                v-model="pickedSources[0].joinCol"
                size="small"
                filterable
                clearable
                placeholder="选关联键列"
                class="wz-key-sel"
                :loading="pickedSources[0].loading"
                @change="guessIfEmpty(0)"
              >
                <el-option v-for="c in pickedSources[0].columns" :key="c.name" :value="c.name" :label="c.name">
                  <span class="mono">{{ c.name }}</span>
                  <span class="wz-remark">{{ c.type }}</span>
                </el-option>
              </el-select>
            </div>
            <div v-if="pickedSources[0].colErr" class="wz-node-err">
              <el-icon><Warning /></el-icon>
              <span>{{ pickedSources[0].colErr }}</span>
              <el-button size="small" link type="primary" @click="retryCols(0)">重试</el-button>
            </div>
            <div class="wz-node-row">
              <span class="wz-node-label">处理链</span>
              <el-button
                size="small"
                link
                type="primary"
                :disabled="!pickedSources[0].joinCol"
                @click="openSteps(0)"
              >{{ stepsLabel(0) }}</el-button>
            </div>
          </div>
        </div>

        <div class="wz-graph-right">
          <div v-for="(s, i) in pickedSources.slice(1)" :key="s.name" class="wz-pair">
            <!-- 连线:与右侧节点同行,永远对齐 -->
            <!-- 两端都选好键才点亮成功色;任一头空缺都保持灰虚线,缺口一眼可见 -->
            <div class="wz-link" :class="{ ok: !!s.joinCol && !!pickedSources[0]?.joinCol }">
              <span class="wz-link-label mono">
                {{ pickedSources[0]?.joinCol || '?' }} ⟷ {{ s.joinCol || '?' }}
              </span>
            </div>
            <div class="wz-node">
              <div class="wz-node-head">
                <el-tag size="small" effect="plain">关联表</el-tag>
                <span class="mono wz-node-name" :title="s.name">{{ s.name }}</span>
                <el-button size="small" link type="primary" title="看这张表的前 10 行"
                  @click="previewTable(s.name)">预览</el-button>
                <el-button size="small" link type="danger" title="移除该表" @click="removePicked(i + 1)">✕</el-button>
              </div>
              <div class="wz-node-row">
                <span class="wz-node-label">关联键</span>
                <el-select
                  v-model="s.joinCol"
                  size="small"
                  filterable
                  clearable
                  placeholder="选关联键列"
                  class="wz-key-sel"
                  :loading="s.loading"
                >
                  <el-option v-for="c in s.columns" :key="c.name" :value="c.name" :label="c.name">
                    <span class="mono">{{ c.name }}</span>
                    <span class="wz-remark">{{ c.type }}</span>
                  </el-option>
                </el-select>
              </div>
              <div v-if="s.colErr" class="wz-node-err">
                <el-icon><Warning /></el-icon>
                <span>{{ s.colErr }}</span>
                <el-button size="small" link type="primary" @click="retryCols(i + 1)">重试</el-button>
              </div>
              <div class="wz-node-row">
                <span class="wz-node-label">处理链</span>
                <el-button
                  size="small"
                  link
                  type="primary"
                  :disabled="!s.joinCol"
                  @click="openSteps(i + 1)"
                >{{ stepsLabel(i + 1) }}</el-button>
              </div>
            </div>
          </div>
          <!-- 到 maxTables 就禁用:JOIN 层数越深生成的 SQL 越难跑,这里先卡住入口 -->
          <div class="wz-add">
            <el-button size="small" :icon="Plus" plain :disabled="pickedSources.length >= maxTables" @click="addTableOpen = true">
              + 加关联表
            </el-button>
          </div>
        </div>
      </div>

      <!-- 加关联表:小型表选择抽屉 -->
      <el-dialog
        v-model="addTableOpen"
        title="加关联表"
        width="420px"
        append-to-body
        :close-on-click-modal="false"
      >
        <el-input v-model="addKw" size="small" clearable placeholder="搜索表名">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <div class="wz-add-list">
          <div
            v-for="t in addCandidates"
            :key="t.name"
            class="wz-add-item"
            @click="addTable(t.name)"
          >
            <span class="mono">{{ t.name }}</span>
            <span v-if="t.remark" class="wz-remark" :title="t.remark">{{ t.remark }}</span>
          </div>
          <el-empty v-if="!addCandidates.length" description="没有可加的表" :image-size="40" />
        </div>
      </el-dialog>

      <!-- 关联键处理链(复用 FieldPipelineEditor,SQL 算子) -->
      <el-dialog
        v-model="stepsOpen"
        :title="stepsIdx !== null && pickedSources[stepsIdx]
          ? `关联键处理链 · ${pickedSources[stepsIdx].name}.${pickedSources[stepsIdx].joinCol}`
          : '关联键处理链'"
        width="min(720px, calc(100vw - 32px))"
        top="6vh"
        append-to-body
        destroy-on-close
      >
        <div v-if="stepsIdx !== null && pickedSources[stepsIdx]">
          <FieldPipelineEditor
            :col="pickedSources[stepsIdx].joinCol"
            :kind="kindOf(stepsIdx)"
            :steps="pickedSources[stepsIdx].joinSteps"
            :prog="[]"
            sql-only
            @update:steps="(v: QueryPipelineStep[]) => (pickedSources[stepsIdx!].joinSteps = v)"
          />
        </div>
        <template #footer>
          <el-button type="primary" @click="stepsOpen = false">完成</el-button>
        </template>
      </el-dialog>
    </div>

    <!-- ── 第 3 步:勾选输出字段(按表分组) ── -->
    <div v-else-if="step === 3">
      <p class="wz-step-hint">
        勾选结果要显示的列(勾完即生成 SQL,无需再回构建器配置)。
        <span class="muted">关联键列已自动加入候选。</span>
      </p>
      <div class="wz-field-toolbar">
        <el-input v-model="colKw" size="small" clearable placeholder="搜索列名" class="wz-kw">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <!-- 「全选可见」只作用于当前搜索命中的列:先搜关键词再批量勾,不必整表全选后再逐条取消 -->
        <el-button size="small" @click="checkAllCols">全选可见</el-button>
        <el-button size="small" @click="checkedCols = []">清空勾选</el-button>
        <span class="muted">已勾 {{ checkedCols.length }} 列</span>
      </div>
      <el-alert
        v-if="noColumnsAtAll && !loadingCols"
        type="error"
        :closable="false"
        show-icon
        class="wz-alert"
      >
        <template #title>
          没读到任何表的列清单,所以没有可勾选的字段。
          <el-button size="small" link type="primary" @click="retryAllCols">重新加载列</el-button>
        </template>
      </el-alert>

      <div class="wz-field-list">
        <div v-for="s in pickedSources" :key="s.name" class="wz-field-group">
          <div class="wz-field-group-head">
            <el-tag size="small" effect="plain" type="info">{{ s.name }}</el-tag>
            <span v-if="s.loading" class="muted">加载中…</span>
            <template v-else-if="s.colErr">
              <el-tag size="small" type="danger" effect="plain">{{ s.colErr }}</el-tag>
              <el-button size="small" link type="primary" @click="retryCols(pickedSources.indexOf(s))">重试</el-button>
            </template>
            <template v-else>
              <span class="muted">{{ s.columns.length }} 列</span>
              <el-button size="small" link type="primary" @click="checkTable(s)">全选本表</el-button>
            </template>
          </div>
          <!--
            不用 el-checkbox-group 包裹:group 会接管子 checkbox 的值绑定,
            而这里需要的是「每项自己知道勾没勾」(值在父级 checkedCols 里)。
            与「② 输出字段」分组区保持同一套写法,避免两处行为不一致。
          -->
          <div class="wz-field-grid">
            <el-checkbox
              v-for="c in filteredCols(s)"
              :key="s.name + '.' + c.name"
              :model-value="checkedCols.includes(s.name + '.' + c.name)"
              @update:model-value="(v: boolean) => toggleChecked(s, c.name, v)"
            >
              <span class="mono">{{ c.name }}</span>
              <span v-if="c.type" class="wz-col-type">{{ c.type }}</span>
              <span v-if="c.comment" class="wz-col-cmt" :title="c.comment">{{ c.comment }}</span>
            </el-checkbox>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 第 4 步:过滤 + 排序(可跳过) ── -->
    <div v-else-if="step === 4">
      <p class="wz-step-hint">
        可选:给结果加过滤条件(如手机号等于某号)和排序。
        <span class="muted">不需要就直接点「生成」。</span>
      </p>

      <h4 class="wz-step-title">过滤条件(全部满足)</h4>
      <!-- 输入区形态由操作符 arity 决定:one/list 共用一个输入框,two(区间)才渲染下界~上界 -->
      <div v-for="(w, i) in whereDraft" :key="i" class="wz-cond-row">
        <el-select v-model="w.column" size="small" filterable placeholder="选列" class="wz-cond-col">
          <el-option v-for="a in fieldAliases" :key="a" :value="a" :label="a" />
        </el-select>
        <!-- 换操作符即清空已填值:值个数或类型语义都变了,旧值留着会被当成真实条件提交 -->
        <el-select v-model="w.op" size="small" class="wz-cond-op" @change="w.value = ''; w.value2 = ''">
          <el-option v-for="o in opsFor(w.column)" :key="o.key" :value="o.key" :label="o.label" />
        </el-select>
        <el-input
          v-if="opArity(w.op) === 'one' || opArity(w.op) === 'list'"
          v-model="w.value"
          size="small"
          :placeholder="opArity(w.op) === 'list' ? '多个值用英文逗号分隔' : '值'"
          class="wz-cond-val"
        />
        <template v-if="opArity(w.op) === 'two'">
          <el-input v-model="w.value" size="small" placeholder="下界" class="wz-cond-val" />
          <span class="muted">~</span>
          <el-input v-model="w.value2" size="small" placeholder="上界" class="wz-cond-val" />
        </template>
        <span v-if="w.op === 'distinct'" class="muted">筛选后去重，多个字段按组合值保留一条</span>
        <el-button size="small" link type="danger" @click="whereDraft.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" :icon="Plus" plain @click="addWhereDraft">+ 加条件</el-button>

      <h4 class="wz-step-title">排序</h4>
      <div v-for="(o, i) in orderDraft" :key="i" class="wz-cond-row">
        <el-select v-model="o.column" size="small" filterable placeholder="选列" class="wz-cond-col">
          <el-option v-for="a in fieldAliases" :key="a" :value="a" :label="a" />
        </el-select>
        <el-radio-group v-model="o.desc" size="small">
          <el-radio-button :value="false">升序</el-radio-button>
          <el-radio-button :value="true">降序</el-radio-button>
        </el-radio-group>
        <el-button size="small" link type="danger" @click="orderDraft.splice(i, 1)">删</el-button>
      </div>
      <el-button size="small" :icon="Plus" plain :disabled="!fieldAliases.length" @click="addOrderDraft">+ 加排序</el-button>
    </div>

    <template #footer>
      <el-button v-if="step >= 2" @click="step--">上一步</el-button>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        v-if="step === 1"
        type="primary"
        :disabled="!picked.length"
        @click="goStep2"
      >
        下一步({{ picked.length }} 表)
      </el-button>
      <el-button v-else-if="step === 2" type="primary" :disabled="!joinOk" @click="goStep3">
        下一步(选输出列)
      </el-button>
      <el-button v-else-if="step === 3" type="primary" :disabled="!checkedCols.length" @click="goStep4">
        下一步(过滤/排序)
      </el-button>
      <!-- 第 4 步整步可跳过,所以这里只卡输出列:过滤/排序留空也能生成 -->
      <el-button v-else type="primary" :loading="applying" :disabled="!checkedCols.length" @click="apply">
        生成查询({{ checkedCols.length }} 列)
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Search, Warning } from '@element-plus/icons-vue'
import { workspaceApi } from '@/api'
import { quoteIdent } from '@/utils/createTable'
import type { BuilderColumn } from '@/composables/useVisualQuery'
import { chTypeToKind, FILTER_OPS_BY_KIND, type FilterOp } from '@/constants/queryBuilderMeta'
import FieldPipelineEditor from './FieldPipelineEditor.vue'
import type { QueryPipelineStep } from '@/types'

interface TableItem {
  name: string
  remark?: string
}

interface PickedSource {
  name: string
  joinCol: string
  /** 关联键处理链(归一化),如 hex(MD5(mdn)) */
  joinSteps: QueryPipelineStep[]
  columns: BuilderColumn[]
  loading: boolean
  /** 列加载失败(DESCRIBE 报错/超时)—— 用于提示重试,而不是静静给个空列表 */
  colErr?: string
}

const props = defineProps<{
  modelValue: boolean
  connId: number
  database: string
  /** 已在构建器里的表(默认预选,避免重复添加) */
  existingTables?: string[]
  /**
   * 构建器里已有的关联配置(表 → 关联键 + 处理链)。
   *
   * ⚠️ 必须带入:向导会重新拉列并重建表对象,若不回填 joinCol/joinSteps,
   *    点「生成查询」就会用空值覆盖构建器里已经配好的关联(表现为「配置没回写�  去」)。
   */
  existingSources?: { table: string; joinCol: string; joinSteps?: QueryPipelineStep[] }[]
  /** 构建器里已选的输出字段(用于回显勾选) */
  existingFields?: { table: string; col: string }[]
  /** 打开时直接跳到第几步(2 = 直接进关系图配置) */
  startStep?: number
  maxTables?: number
}>()

/**
 * 预览某张表的前 10 行。
 *
 * 用途:「配置关联键」这一步光看列名很难判断该选哪个(尤其 mdn / phone / mdn_md5 这种),
 * 先扫一眼真实数据就一目了然。用通用查询接口(数据探索页同一个),不新增后端。
 * 只读:allowWrite=false,而且语句就是 SELECT ... LIMIT 10。
 */
const pvVisible = ref(false)
const pvLoading = ref(false)
const pvTitle = ref('')
const pvColumns = ref<string[]>([])
const pvRows = ref<unknown[][]>([])

async function previewTable(table: string) {
  pvVisible.value = true
  pvLoading.value = true
  pvTitle.value = table
  pvColumns.value = []
  pvRows.value = []
  try {
    // 库名表名都加反引号:表名可能带特殊字符,裸拼会语法错
    const sql = 'SELECT * FROM ' + quoteIdent(props.database) + '.' + quoteIdent(table) + ' LIMIT 10'
    const res = await workspaceApi.query(props.connId, sql, false, props.database, 1, 10)
    // 返回形状兜一下:有的封装直接给 { columns, rows },有的给 { results: [{...}] }
    const r: Record<string, any> = (res as Record<string, any>) ?? {}
    const first = Array.isArray(r.results) && r.results.length ? r.results[0] : r
    pvColumns.value = first?.columns ?? []
    pvRows.value = first?.rows ?? []
  } catch (e: any) {
    ElMessage.error('预览失败:' + (e?.message ?? e))
  } finally {
    pvLoading.value = false
  }
}

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  /**
   * 确定向导:按顺序返回表清单、各自关联键+处理链、输出字段、过滤、排序。
   * picked[0] 是主表;各表 joinCol 归一化(含 joinSteps)后按 mdn_norm 对齐 JOIN。
   */
  (
    e: 'apply',
    picked: { name: string; joinCol: string; joinSteps: QueryPipelineStep[] }[],
    fields: { table: string; col: string; alias: string }[],
    where: { column: string; op: string; value: string; value2?: string }[],
    orderBy: { column: string; desc: boolean }[]
  ): void
}>()

const maxTables = computed(() => props.maxTables ?? 6)

const step = ref(1)
const kw = ref('')
const loading = ref(false)
const tables = ref<TableItem[]>([])
const picked = ref<string[]>([])
const pickedSources = ref<PickedSource[]>([])
const loadingCols = ref(false)
const applying = ref(false)
// —— 第 3 步:输出字段 ——
const colKw = ref('')
/** 勾选的列,格式「表名.列名」 */
const checkedCols = ref<string[]>([])
// —— 第 4 步:过滤/排序草稿 ——
const whereDraft = ref<WzWhere[]>([])
const orderDraft = ref<WzOrder[]>([])
// —— 加关联表 ——
const addTableOpen = ref(false)
const addKw = ref('')
// —— 关联键处理链 ——
const stepsOpen = ref(false)
const stepsIdx = ref<number | null>(null)

interface WzWhere {
  column: string
  op: string
  value: string
  value2?: string
}
interface WzOrder {
  column: string
  desc: boolean
}

/** 第 1 步表搜索:表名与注释都参与匹配(注释常写业务含义,按业务词找表比记表名快) */
const filtered = computed(() => {
  const k = kw.value.trim().toLowerCase()
  if (!k) return tables.value
  return tables.value.filter((t) => t.name.toLowerCase().includes(k) || (t.remark ?? '').toLowerCase().includes(k))
})

/** 多表时每张表都应有关联键;单表可留空(不 JOIN) */
const joinOk = computed(() => {
  if (pickedSources.value.length <= 1) return true
  return pickedSources.value.every((s) => !!s.joinCol)
})
/** 还没选关联键的表名(用于在「下一步」被禁用时说清原因) */
const missingKeyTables = computed(() =>
  pickedSources.value.length > 1 ? pickedSources.value.filter((s) => !s.joinCol).map((s) => s.name) : []
)

/** 打开向导时:拉表清单,预选已有表(顺序保持:主表在前) */
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    // 每次打开都从干净状态重来:上轮的勾选/草稿若残留,配合 startStep 直接跳步会串味
    step.value = 1
    kw.value = ''
    colKw.value = ''
    picked.value = []
    pickedSources.value = []
    checkedCols.value = []
    whereDraft.value = []
    orderDraft.value = []
    if (tables.value.length) {
      // 表清单已缓存:还要根据 startStep 决定直接进哪步
      if ((props.startStep ?? 1) >= 2) await jumpToStep2()
      return
    }
    loading.value = true
    try {
      tables.value = await workspaceApi.tables(props.connId, props.database)
      const existing = props.existingTables ?? []
      // 只预选数据库里仍存在的表:构建器中的旧表名可能已被删,塞进 picked 会让后续拉列全报错
      picked.value = existing.filter((n) => tables.value.some((t) => t.name === n)).slice(0, maxTables.value)
    } catch {
      // 拉表失败降级为空列表:第 1 步显示「没有匹配的表」即可,不该把整个对话框卡住
      tables.value = []
    } finally {
      loading.value = false
    }
    if ((props.startStep ?? 1) >= 2) await jumpToStep2()
  },
  { immediate: true }
)

/** 直接跳到关系图(从关联页签的「配置关联关系」进来):预选当前构建器里的表 */
async function jumpToStep2() {
  const existing = props.existingTables ?? []
  if (existing.length) picked.value = existing.slice(0, maxTables.value)
  if (!picked.value.length) return // 没表就留在第 1 步
  // 保留构建器里已有的键/处理链(父级传入的只是表名,键靠预填/猜测补齐)
  // 进第 2 步前必须把列拉齐:关联键下拉、处理链可用算子都依赖列清单与列类型
  loadingCols.value = true
  try {
    await ensureSources(picked.value.slice(0, maxTables.value))
  } finally {
    loadingCols.value = false
  }
  // 单表没有关联可配 → 跳过关系图,直接进输出列
  if (picked.value.length === 1) {
    await goStep3FromSingle()
    return
  }
  step.value = 2
}

/** 第 2 步前的准备:按勾选顺序拉列,自动猜关联键 */
async function goStep2() {
  // 只取前 maxTables 张:多勾的不报错直接截断,保证与 pickedSources 的顺序严格一致
  const names = picked.value.slice(0, maxTables.value)
  // 单表:无关联可配 → 直接跳到第 3 步
  if (names.length === 1) {
    await ensureSources(names)
    await goStep3FromSingle()
    return
  }
  loadingCols.value = true
  try {
    await ensureSources(names)
  } finally {
    loadingCols.value = false
  }
  step.value = 2
}

/**
 * 拉取某张表的列清单(DESCRIBE)。
 *
 * ⚠️ 失败必须【可见】:以前 catch 里静静把 columns 置空,
 *    进入第 3 步就只看到「全选本表」按铮、一个勾选框都没有，
 *    用户以为界面坏了。现在记下错误并给重试入口。
 */
async function fetchColumns(s: PickedSource) {
  s.loading = true
  s.colErr = ''
  try {
    const res = await workspaceApi.schemaSilent(props.connId, props.database, s.name)
    // schemaSilent 给的是 columns + rows 的原始表结构,按下标取值;comment 列可能不存在
    const idxName = res.columns.indexOf('name')
    const idxType = res.columns.indexOf('type')
    const idxCmt = res.columns.indexOf('comment')
    s.columns = res.rows.map((r: unknown[]) => {
      const n = String(r[idxName] ?? '')
      const t = String(r[idxType] ?? '')
      const cmt = idxCmt >= 0 ? String(r[idxCmt] ?? '') : ''
      return { name: n, type: t, kind: chTypeToKind(t), comment: cmt || undefined }
    })
    if (!s.columns.length) s.colErr = '没读到列(表可能不存在或没权限)'
    guessJoinCol(s)
  } catch (e) {
    s.columns = []
    s.colErr = e instanceof Error ? e.message : '列加载失败'
  } finally {
    s.loading = false
  }
}

/** 单张表重新拉列(失败后的重试按钮) */
async function retryCols(i: number) {
  const s = pickedSources.value[i]
  if (s) await fetchColumns(s)
}

/** 是否所有表都没拿到列(第 3 步无法勾选时给整体提示 + 重试) */
const noColumnsAtAll = computed(
  () => pickedSources.value.length > 0 && pickedSources.value.every((s) => !s.columns.length)
)
/** 重新拉全部表的列 */
async function retryAllCols() {
  loadingCols.value = true
  try {
    await Promise.all(pickedSources.value.map((s) => fetchColumns(s)))
  } finally {
    loadingCols.value = false
  }
}

/** 将目标表名与 pickedSources 对齐：新增表拉取列，已有表保留关联键和处理链配置。 */
async function ensureSources(names: string[]) {
  const existingMap = new Map(pickedSources.value.map((s) => [s.name, s]))
  // 构建器里的已有配置:表名 → 关联键/处理链(用于回填,避免重建时丢失)
  const cfgMap = new Map((props.existingSources ?? []).map((s) => [s.table, s]))
  // 优先复用旧对象:缓存的 joinCol / joinSteps / columns 不能因为重建列表而丢失
  const next: PickedSource[] = names.map((n) => {
    const cached = existingMap.get(n)
    if (cached) return cached
    const cfg = cfgMap.get(n)
    return {
      name: n,
      joinCol: cfg?.joinCol ?? '',
      // 逐层拷贝 args:直接引用父级对象的话,向导里编辑处理链会顺手改到构建器的原状态
      joinSteps: (cfg?.joinSteps ?? []).map((st) => ({
        fn: st.fn,
        args: (st.args ?? []).map((a) => ({ ...a }))
      })),
      columns: [],
      loading: true
    }
  })
  pickedSources.value = next
  await Promise.all(
    next.map(async (s) => {
      // 回退再进第 2 步、追加表都会重跑本函数,这里挡掉重复的 DESCRIBE 往返
      if (!s.loading && s.columns.length) return s // 已有列缓存
      // 已回填关联键时也仍需列清单(否则第 3 步无法勾字段),但【不】重新猜键
      // keepCol 是调用前的键值:为空说明这是首次拉列,此时才接受自动猜出的结果
      const keepCol = s.joinCol
      await fetchColumns(s)
      s.joinCol = keepCol || s.joinCol
      return s
    })
  )
  // 首次进入时回显构建器里已选的输出字段(只认当前选中的表;已勾过就不覆盖)
  // 用 Set 过滤而非直接赋值:构建器里的旧表已不在本次选表中,其字段不能进来
  if (!checkedCols.value.length) {
    const pickedNames = new Set(names)
    const restore = (props.existingFields ?? [])
      .filter((f) => pickedNames.has(f.table))
      .map((f) => f.table + '.' + f.col)
    if (restore.length) checkedCols.value = restore
  }
}

/** 单表跳步:预勾全部列?不 —— 只预勾主键猜测列,其余用户勾 */
async function goStep3FromSingle() {
  const s = pickedSources.value[0]
  // 关联键列默认一并勾上:后续过滤/展示几乎都要用到它,漏勾得回头补
  const next = new Set(checkedCols.value)
  if (s?.joinCol) next.add(s.name + '.' + s.joinCol)
  checkedCols.value = [...next]
  step.value = 3
}

/** 与 useVisualQuery.autoPickJoinCol 同规则:优先 mdn 系列列 */
function guessJoinCol(s: PickedSource) {
  // 只在空值时猜:用户手选的键、或父级回填的键都不能被自动猜测覆盖
  if (s.joinCol) return
  const guess = s.columns.find((c) => /^(mdn|mdn_norm|phone|mobile|手机号)$/i.test(c.name))
  if (guess) s.joinCol = guess.name
}

function guessIfEmpty(idx: number) {
  const s = pickedSources.value[idx]
  if (s) guessJoinCol(s)
}

// —— 关联键处理链 ——
function stepsLabel(idx: number): string {
  const n = pickedSources.value[idx]?.joinSteps.length ?? 0
  // 不配处理链是正常状态(两表键格式本就一致),所以用中性文案而非告警色提示
  return n ? `${n} 步处理` : '未配置(格式一致可不动)'
}
function kindOf(idx: number): BuilderColumn['kind'] {
  // 处理链编辑器按列类型筛可用算子;列未加载或键未选时退化为字符串,保证编辑器仍可用
  const s = pickedSources.value[idx]
  const c = s?.columns.find((x) => x.name === s.joinCol)
  return c?.kind ?? 'string'
}
function openSteps(idx: number) {
  stepsIdx.value = idx
  stepsOpen.value = true
}

// —— 加关联表 ——
/** 候选 = 全表清单去掉已在图里的表(主表同样占名额);搜索同样覆盖注释 */
const addCandidates = computed(() => {
  const inUse = new Set(pickedSources.value.map((s) => s.name))
  const k = addKw.value.trim().toLowerCase()
  return tables.value.filter(
    (t) => !inUse.has(t.name) && (!k || t.name.toLowerCase().includes(k) || (t.remark ?? '').toLowerCase().includes(k))
  )
})
async function addTable(name: string) {
  // 入口按钮已按上限禁用,这里再挡一次:回车、程序化触发等路径仍可能走到
  if (pickedSources.value.length >= maxTables.value) return
  loadingCols.value = true
  try {
    await ensureSources([...pickedSources.value.map((s) => s.name), name])
  } finally {
    loadingCols.value = false
  }
  addTableOpen.value = false
  addKw.value = ''
}

function removePicked(i: number) {
  const name = pickedSources.value[i]?.name
  pickedSources.value.splice(i, 1)
  if (name) {
    // 移除表 → 该表的勾选列一并清理
    checkedCols.value = checkedCols.value.filter((k) => !k.startsWith(name + '.'))
  }
  // 表被删光后关系图无从渲染,退回第 1 步重选,而不是把用户留在空白的第 2 步
  if (pickedSources.value.length === 0) step.value = 1
}

// —— 第 3 步 ——
function filteredCols(s: PickedSource) {
  const k = colKw.value.trim().toLowerCase()
  if (!k) return s.columns
  // 注释也参与搜索
  return s.columns.filter((c) => c.name.toLowerCase().includes(k) || (c.comment ?? '').toLowerCase().includes(k))
}

/** 勾/取消单个字段(表名.列名 作为唯一键) */
function toggleChecked(s: PickedSource, col: string, v: boolean) {
  const key = s.name + '.' + col
  const next = new Set(checkedCols.value)
  if (v) next.add(key)
  else next.delete(key)
  checkedCols.value = [...next]
}

function checkTable(s: PickedSource) {
  // 「全选本表」不受列搜索影响:勾的是该表全部列,避免搜了关键字后漏勾其余列
  const keys = s.columns.map((c) => s.name + '.' + c.name)
  const next = new Set(checkedCols.value)
  for (const k of keys) next.add(k)
  checkedCols.value = [...next]
}

/** 只勾当前可见列:先按关键词搜窄再批量勾,比整表全选后逐条取消省事 */
function checkAllCols() {
  const next = new Set(checkedCols.value)
  for (const s of pickedSources.value) for (const c of filteredCols(s)) next.add(s.name + '.' + c.name)
  checkedCols.value = [...next]
}

function goStep3() {
  const next = new Set(checkedCols.value)
  for (const s of pickedSources.value) {
    if (s.joinCol) next.add(s.name + '.' + s.joinCol)
  }
  checkedCols.value = [...next]
  step.value = 3
}

/** 勾选列对应的别名清单(顺序即输出顺序) */
// 别名只保留点号之后的列名(表名不含点,故用 indexOf 定位);跨表重名留到 apply 里统一消解
const fieldAliases = computed(() => checkedCols.value.map((k) => k.slice(k.indexOf('.') + 1)))

/** 某列可选的操作符(按列类型) */
function opsFor(alias: string): FilterOp[] {
  // 这里只拿得到列名,故取第一张命中该列的表;pickedSources 主表在前,跨表同名时以主表为准
  for (const s of pickedSources.value) {
    const c = s.columns.find((x) => x.name === alias)
    if (c) return FILTER_OPS_BY_KIND[c.kind] ?? FILTER_OPS_BY_KIND.string
  }
  return FILTER_OPS_BY_KIND.string
}
function opArity(op: string): string {
  // 算子元数据按列类型分组存放,这里跨组查「要几个输入值」;查不到按单值兜底
  for (const ops of Object.values(FILTER_OPS_BY_KIND)) {
    const o = ops.find((x) => x.key === op)
    if (o) return o.arity
  }
  return 'one'
}

function addWhereDraft() {
  // 默认带上第一个输出字段及其首选算子,新行落地即可填值,少两次点击
  const col = fieldAliases.value[0] ?? ''
  const ops = opsFor(col)
  whereDraft.value.push({ column: col, op: ops[0]?.key ?? '=', value: '' })
}
function addOrderDraft() {
  // 同一列重复排序没有意义,还会生成冗余 ORDER BY;所有列都用过就不再新增
  const used = new Set(orderDraft.value.map((o) => o.column))
  const col = fieldAliases.value.find((a) => !used.has(a))
  if (col) orderDraft.value.push({ column: col, desc: true })
}

function goStep4() {
  step.value = 4
}

/** 生成:回传表+关联键(含处理链)+输出字段+过滤+排序 */
function apply() {
  applying.value = true
  try {
    const pickedOut = pickedSources.value.map((s) => ({
      name: s.name,
      joinCol: s.joinCol,
      joinSteps: s.joinSteps.map((st) => ({ fn: st.fn, args: (st.args ?? []).map((a) => ({ ...a })) }))
    }))
    // 勾选顺序就是输出顺序;别名 = 列名(重复列名自动去重)
    // ⚠️ 只取【当前选中表】的勾选:残留的旧表名会让 sourceIdx 落到错表(?? 0 全部变成主表)
    const validTables = new Set(pickedSources.value.map((s) => s.name))
    const taken = new Set<string>()
    const fields = checkedCols.value
      .filter((k) => validTables.has(k.slice(0, k.indexOf('.'))))
      .map((k) => {
        const dot = k.indexOf('.')
        const table = k.slice(0, dot)
        const col = k.slice(dot + 1)
        // 跨表同名列(如两张表都有 mdn)自动加序号后缀,否则结果集里列名相撞
        let alias = col
        let i = 1
        while (taken.has(alias)) alias = col + '_' + i++
        taken.add(alias)
        return { table, col, alias }
      })
    // 值没填完的条件行直接丢弃,避免生成 col = '' 这类恒假条件;is_null/is_not_null 本就不需要值
    const where = whereDraft.value
      .filter((w) => w.column && (['is_null', 'is_not_null', 'distinct'].includes(w.op) || w.value !== ''))
      .map((w) => ({
        column: w.column,
        op: w.op,
        value: w.value,
        ...(w.op === 'between' && w.value2 ? { value2: w.value2 } : {})
      }))
    // 排序同样丢掉没选列的空行,不因为多一行草稿就报错
    const orderBy = orderDraft.value.filter((o) => o.column)
    emit('apply', pickedOut, fields, where, orderBy)
    emit('update:modelValue', false)
  } finally {
    applying.value = false
  }
}

defineExpose({
  /** 刷新表清单(表结构变更后调用) */
  // 只清表清单缓存,下次打开重新拉;列缓存会随打开时的 pickedSources 重建自然失效
  reload: () => {
    tables.value = []
  }
})
</script>

<style scoped>
.wz-step-hint {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.wz-alert {
  margin-bottom: 10px;
}
/* 单表列加载失败的内联提示 */
.wz-node-err {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--el-color-danger);
}
.wz-kw {
  width: 260px;
  margin-bottom: 8px;
}
.wz-table-list {
  max-height: 340px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 6px 10px;
}
.wz-table-item {
  padding: 3px 0;
}
.wz-remark {
  margin-left: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* ── 第 2 步:关系图 ──
   左主表 + 右侧每张关联表一行(连线+节点成对),行内天然对齐,不会堆积 */
.wz-graph {
  display: flex;
  gap: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow-x: auto;
}
.wz-graph-left {
  flex: none;
  align-self: center;
  padding-right: 14px;
  /* 与右侧行区隔开但视觉上是一体 */
  border-right: 2px solid var(--el-border-color-light);
}
.wz-graph-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wz-pair {
  display: flex;
  align-items: center;
  gap: 0;
}
/* 连线:与同行节点垂直居中对齐 */
.wz-link {
  flex: none;
  width: 110px;
  height: 2px;
  position: relative;
  background: repeating-linear-gradient(90deg, var(--el-border-color) 0 6px, transparent 6px 10px);
  border-radius: 1px;
  transition: background 0.2s;
}
.wz-link.ok {
  background: repeating-linear-gradient(90deg, var(--el-color-success) 0 6px, transparent 6px 10px);
}
.wz-link-label {
  position: absolute;
  top: -16px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.wz-add {
  padding-left: 110px; /* 与连线同宽,「+加关联表」与节点对齐 */
}
.wz-node {
  flex: none;
  min-width: 240px;
  max-width: 300px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.wz-node-main {
  border-color: var(--el-color-warning-light-5);
  box-shadow: 0 1px 4px var(--el-color-warning-light-7);
}
.wz-node-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.wz-node-name {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wz-node-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 标签定宽:所有下拉框纵向对齐(含主表置底重排后) */
.wz-node-label {
  flex: none;
  width: 52px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.wz-key-sel {
  flex: 1;
  min-width: 0;
}
/* 连线已合并到 .wz-pair/.wz-link 结构(见上方关系图注释) */

/* ── 加表抽屉 ── */
.wz-add-list {
  max-height: 300px;
  overflow: auto;
  margin-top: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 4px 8px;
}
.wz-add-item {
  padding: 5px 4px;
  cursor: pointer;
  border-radius: 4px;
}
.wz-add-item:hover {
  background: var(--el-fill-color);
}

/* ── 第 3 步 ── */
.wz-field-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.wz-field-list {
  max-height: 360px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.wz-field-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.wz-field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 2px 10px;
}
/*
 * 勾选项防溢出:列名+类型+注释可能比单元宽,不收紧就会盖住相邻的勾选框。
 * 让 checkbox 自身可收缩,注释省略号截断(悬停 title 看全文)。
 */
.wz-field-grid :deep(.el-checkbox) {
  display: flex;
  align-items: baseline;
  min-width: 0;
  margin-right: 0;
  overflow: hidden;
}
.wz-field-grid :deep(.el-checkbox__input) {
  flex: none;
}
.wz-field-grid :deep(.el-checkbox__label) {
  flex: 1;
  min-width: 0;
  display: inline-flex;
  align-items: baseline;
  overflow: hidden;
}
.wz-col-type {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
/* 列名过长也要能截断,否则会把注释/勾选框挤出去 */
.wz-field-grid :deep(.el-checkbox__label) > span:first-child {
  flex: none;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 列注释:灰色小字,占剩余宽度并截断 */
.wz-col-cmt {
  flex: 1;
  min-width: 24px;
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 第 4 步 ── */
.wz-step-title {
  margin: 14px 0 8px;
  font-size: 14px;
}
.wz-cond-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.wz-cond-col {
  width: 200px;
}
.wz-cond-op {
  width: 120px;
}
.wz-cond-val {
  width: 170px;
}

.wz-pv-wrap { min-height: 120px; }
.wz-pv-cell { font-size: 13px; word-break: break-all; }
.wz-pv-empty { padding: 24px; text-align: center; color: var(--el-text-color-placeholder); font-size: 13px; }
</style>
