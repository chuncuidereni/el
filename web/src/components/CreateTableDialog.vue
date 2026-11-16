<!--
  CreateTableDialog.vue

  职责：建表弹窗：编辑数据库、表名、字段与 DDL 预览，并提交受控建表请求。
  边界：由 props 初始化表配置和 DDL，并通过事件交回提交结果；DDL 导入、ODS 固定列和引擎参数由 watch 同步，关闭时重置临时状态。
-->

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="1000px"
    top="6vh"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <!-- 两种录入方式互斥:表格方式由配置实时生成 DDL,DDL 方式直接手写,复杂语法走后者 -->
    <el-segmented v-model="mode" :options="modeOptions" class="mode-switch" />

    <!-- ============ 表格方式 ============ -->
    <div v-if="mode === 'grid'" class="grid-mode">
      <div class="grid-toolbar">
        <el-button size="small" :icon="Upload" @click="importVisible = true">从 DDL 导入</el-button>
        <el-button size="small" :icon="Files" @click="openImportDs">从数据源导入</el-button>
        <span class="grid-toolbar-hint">粘贴建表语句,或从已有数据源读取表结构</span>
      </div>
      <div class="ods-row" :title="odsHint">
        <span class="ods-label">ODS 贴源表</span>
        <!-- 从标准源表（ods_data）页进入时强制开启:该入口建出来的必然是贴源表,不给关闭的余地 -->
        <el-switch v-model="isOds" :disabled="asOds" />
        <span v-if="asOds" class="grid-toolbar-hint ods-fixed">已开启(贴源表模式固定不可关):自动加 ods_ 前缀及入库时间/来源字段,按日分区</span>
        <span v-else class="grid-toolbar-hint">{{ odsHintShort }}</span>
      </div>
      <div class="table-settings">
        <!-- 库名隐含在调用方(该对话框始终建在传入的库内),弱化库的存在,不展示 -->
        <div class="setting-item">
          <span class="setting-label">表名</span>
          <div class="tbl-name-input">
            <span v-if="isOds" class="ods-fix">ods_</span>
            <el-input v-model="tableName" :placeholder="isOds ? '在此输入,自动加 ods_ 前缀' : '例如 orders'" @blur="onTableNameBlur" />
          </div>
        </div>
        <div class="setting-item">
          <span class="setting-label">引擎</span>
          <el-select v-model="engine" filterable style="width: 100%">
            <el-option v-for="e in engineOptions" :key="e" :label="e" :value="e" />
          </el-select>
        </div>
        <!-- 仅部分引擎带参数,且各引擎所需参数的个数与含义不同,故按引擎分支渲染 -->
        <div v-if="engineNeedsParam" class="setting-item">
          <span class="setting-label">引擎参数</span>
          <template v-if="engine === 'CollapsingMergeTree'">
            <el-select v-model="paramSign" filterable style="width: 100%" placeholder="标记字段(Sign)">
              <el-option v-for="c in colNameOptions" :key="c" :label="c" :value="c" />
            </el-select>
          </template>
          <template v-else-if="engine === 'VersionedCollapsingMergeTree'">
            <div class="param-pair">
              <el-select v-model="paramSign" filterable placeholder="Sign" style="flex: 1">
                <el-option v-for="c in colNameOptions" :key="c" :label="c" :value="c" />
              </el-select>
              <el-select v-model="paramVersion" filterable placeholder="Version" style="flex: 1">
                <el-option v-for="c in colNameOptions" :key="c" :label="c" :value="c" />
              </el-select>
            </div>
          </template>
          <template v-else-if="engine === 'ReplacingMergeTree'">
            <el-select v-model="paramVersion" clearable filterable placeholder="可选版本列" style="width: 100%">
              <el-option v-for="c in colNameOptions" :key="c" :label="c" :value="c" />
            </el-select>
          </template>
          <template v-else-if="engine === 'SummingMergeTree'">
            <el-select v-model="paramSumming" multiple clearable filterable collapse-tags placeholder="可选汇总列" style="width: 100%">
              <el-option v-for="c in colNameOptions" :key="c" :label="c" :value="c" />
            </el-select>
          </template>
        </div>
      </div>
      <div class="table-remark">
        <span class="setting-label">表注释</span>
        <el-input v-model="tableRemark" placeholder="可选,显示在表节点上" />
      </div>

      <div v-if="isMergeTree" class="key-settings">
        <div class="setting-item">
          <span class="setting-label">分区键</span>
          <el-select
            v-model="partitionExpr"
            filterable
            allow-create
            default-first-option
            clearable
            style="width: 100%"
            placeholder="可选,如 toYYYYMMDD(created)"
          >
            <el-option v-for="o in partitionOptions" :key="o" :label="o" :value="o" />
          </el-select>
        </div>
        <div class="setting-item">
          <span class="setting-label">排序键</span>
          <el-select v-model="sortingKey" multiple filterable collapse-tags style="width: 100%" placeholder="必选(ORDER BY)">
            <el-option v-for="c in colNameOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </div>
        <div class="setting-item">
          <span class="setting-label">主键</span>
          <el-select v-model="primaryKey" multiple filterable collapse-tags style="width: 100%" placeholder="可空,默认同排序键">
            <el-option v-for="c in colNameOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </div>
      </div>

      <!-- 每行直接双向绑定 columns,输入会实时驱动下方 DDL 预览;贴源固定列按 isOdsRo 置灰以守住贴源约定。
           重复的字段名把冲突行整体标红(见 dup-row),提示"不允许"而不是把两行都锁掉。 -->
      <el-table :data="columns" size="small" border class="cols-table" :row-class-name="dupRowClass">
        <!-- 仅当从数据源/DDL 导入(行带 orig 来源)时显示“原字段”,直接手填不显示此列 -->
        <el-table-column v-if="hasOrig" label="原字段" min-width="160">
          <template #default="{ row }"><span class="mono orig-cell">{{ row.orig || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="字段名" min-width="210">
          <template #default="{ row }">
            <el-input v-model="row.name" placeholder="字段名" :disabled="isOdsRo(row)" :class="{ 'dup-name': isDupName(row.name) }" />
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="180">
          <template #default="{ row }">
            <el-select v-model="row.type" filterable allow-create default-first-option placeholder="选择或输入" style="width: 100%" :disabled="isOdsRo(row)">
              <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="可空" width="60" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.nullable" :disabled="isOdsRo(row)" />
          </template>
        </el-table-column>
        <el-table-column label="默认值" min-width="130">
          <template #default="{ row }">
            <el-input v-model="row.defaultValue" placeholder="如 0 / now() / 'x'" :disabled="isOdsRo(row)" />
          </template>
        </el-table-column>
        <el-table-column label="列注释" min-width="150">
          <template #default="{ row }">
            <el-input v-model="row.comment" placeholder="可选" :disabled="isOdsRo(row)" />
          </template>
        </el-table-column>
        <!-- 贴源固定列不渲染删除按钮:一旦删掉,列/分区/主键的贴源约定就得靠校验去救 -->
        <el-table-column width="52" align="center">
          <template #default="{ $index, row }">
            <el-button v-if="!isOdsRo(row)" link type="danger" :icon="Delete" @click="removeColumn($index)" />
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" :icon="Plus" class="add-col" @click="addColumn">添加字段</el-button>

      <div class="ddl-preview">
        <!-- 表头整行可点切换折叠;复制按钮加 .stop 阻止冒泡,否则点一次复制会顺带把预览收起 -->
        <div class="preview-head" @click="previewOpen = !previewOpen">
          <span class="preview-arrow">{{ previewOpen ? '▾' : '▸' }}</span>
          <span>DDL 预览</span>
          <el-button
            v-if="!previewOpen"
            size="small"
            link
            type="primary"
            class="preview-copy"
            @click.stop="copyDdl"
          >复制</el-button>
        </div>
        <pre v-if="previewOpen" class="ddl-hl" v-html="highlightedDDL"></pre>
      </div>
    </div>

    <!-- ============ DDL 语句方式 ============ -->
    <!-- DDL 方式不做字段级校验,内容原样提交,给表格方式表达不了的语法留出口 -->
    <div v-else class="ddl-mode">
      <div class="ddl-hint">填写建表 DDL,支持多行。示例:</div>
      <pre class="example">CREATE TABLE IF NOT EXISTS new_table
(
    id UInt64,
    name String,
    created DateTime DEFAULT now()
)
ENGINE = MergeTree
ORDER BY id</pre>
      <div class="ddl-editor">
        <CodeMirrorEditor v-model="ddlText" />
      </div>
    </div>

    <!-- 创建期间按钮 loading 兼作防重提交:建表不可回滚,重复提交只会多赚一次「表已存在」 -->
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="creating" @click="onCreate">{{ mode === 'grid' ? '创建表' : '创建' }}</el-button>
    </template>

    <!-- 从数据源导入 -->
    <el-dialog
      v-model="importDsVisible"
      title="从数据源读取表结构"
      width="560px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form label-width="104px">
        <el-form-item label="数据源分组" required>
          <el-select v-model="dsGroupSel" filterable placeholder="先选数据源分组" style="width: 100%" @change="onDsGroupChange">
            <el-option value="default" :label="`默认分组 (${dsInGroup('default').length})`" />
            <el-option v-for="g in dsGroups" :key="g.id" :value="String(g.id)" :label="`${g.name} (${dsInGroup(String(g.id)).length})`" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="importDsId" filterable placeholder="选择分组下的数据源" style="width: 100%" @change="onImportDsChange">
            <el-option v-for="ds in dsInGroup(dsGroupSel)" :key="ds.id" :value="ds.id" :label="`${ds.name} (${ds.code})`" />
          </el-select>
        </el-form-item>
        <!-- 源列是异步取回的;为空(还没回来或取失败)时既不展示,也不允许提交 -->
        <el-form-item v-if="dsCols.length" label="源列">
          <div class="ds-col-list">
            <el-tag v-for="c in dsCols" :key="c.name" size="small" effect="plain">
              {{ c.name }} · {{ c.type }}
            </el-tag>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDsVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!dsCols.length" @click="onImportDs">读取并填充</el-button>
      </template>
    </el-dialog>

    <!-- 从 DDL 导入 -->
    <!-- 识别失败只提示、不动现有表格:弹窗保持打开,用户可对照错误改原文再来一次 -->
    <el-dialog
      v-model="importVisible"
      title="从 DDL 导入"
      width="720px"
      append-to-body
      :close-on-click-modal="false"
    >
      <div class="ddl-hint">粘贴一条 CREATE TABLE 语句(可从表详情的「建表语句 DDL」复制),点击识别后自动填充上方表格。</div>
      <div class="import-editor">
        <CodeMirrorEditor v-model="importDdl" />
      </div>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" @click="onImport">识别并填充</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Files, Plus, Upload } from '@element-plus/icons-vue'
import { dataSourcesApi, dsGroupsApi, workspaceApi } from '@/api'
import type { DataSource } from '@/types'
import CodeMirrorEditor from '@/components/CodeMirrorEditor.vue'
import { confirmAction } from '@/utils/confirm'
import { fileSourceColumns, type FileSourceColumn } from '@/utils/fileColumns'
import {
  type ColumnRow,
  type TableState,
  ODS_COLS,
  ENGINE_OPTIONS,
  TYPE_OPTIONS,
  engineNeedsParam as engineNeedsParamOf,
  isMergeTree as isMergeTreeOf,
  normalizeOdsName,
  generateDDL,
  validateGrid as validateGridState,
  duplicateColumnError,
  partitionOptionsOf,
  parseCreateDDL,
  parseEngineParams,
  highlightDDL,
} from '@/utils/createTable'

const props = defineProps<{
  modelValue: boolean
  connId: number
  db: string
  /** 作为贴源表新建:标题/默认 ODS 模式(标准源表（ods_data）页用,弱化库) */
  asOds?: boolean
}>()

const dialogTitle = computed(() => (props.asOds ? '新建贴源表' : '新建表'))
const odsHintShort = '自动添加 ods_insert_at / ods_source_id(SFTP 目标再加 path)并默认按 ods_insert_at 分区'
const odsHint = '自动添加 ods_insert_at(入库时间,默认 now())与 ods_source_id(来源=数据源标识);若该表将作为 SFTP 数据源的同步目标,首次同步时会自动补一列 path(来源文件路径)。贴源表默认按 ods_insert_at 按日分区。'

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'created'): void
}>()

type Mode = 'grid' | 'ddl'
const mode = ref<Mode>('grid')
const modeOptions = [
  { label: '表格方式', value: 'grid' },
  { label: 'DDL 语句', value: 'ddl' },
]

const creating = ref(false)
const tableName = ref('')
const tableRemark = ref('')
const engine = ref('MergeTree')
const partitionExpr = ref('') // 分区表达式,支持 toYYYYMMDD(created) 等函数
const sortingKey = ref<string[]>([])
const primaryKey = ref<string[]>([])
const ddlText = ref('')
const previewOpen = ref(false) // DDL 预览默认折叠

async function copyDdl() {
  const txt = generatedDDL.value
  // 剪贴板 API 在非 HTTPS 或未授权时会被拒绝,复制只是便利功能,失败不该打断建表流程
  try {
    await navigator.clipboard.writeText(txt)
    ElMessage.success('已复制 DDL')
  } catch {
    /* 忽略 */
  }
}

// DDL 识别
const importVisible = ref(false)
const importDdl = ref('')

// —— 从数据源导入表结构 ——
const importDsVisible = ref(false)
const importDsId = ref<number | null>(null)

function openImportDs() {
  importDsId.value = null
  dsCols.value = []
  dsGroupSel.value = 'default'
  importDsVisible.value = true
}
const localDataSources = ref<DataSource[]>([])
const dsCols = ref<FileSourceColumn[]>([])
// 数据源分组(先选分组再选源)
const dsGroupSel = ref('default')
const dsGroups = ref<{ id?: number; name: string; count?: number }[]>([])

/** 某分组下的数据源('default'=默认分组,'all'=全部) */
function dsInGroup(v: string): DataSource[] {
  if (v === 'all') return localDataSources.value
  // 分组只是前端组织形式(后端无此概念),默认分组约定用 groupId 为空表示
  if (v === 'default') return localDataSources.value.filter((d) => !d.groupId)
  const gid = Number(v)
  return localDataSources.value.filter((d) => d.groupId === gid)
}

// 换分组就丢弃已选源与已取回的列,避免拿 A 组源的列去填充 B 组的表结构
function onDsGroupChange() {
  importDsId.value = null
  dsCols.value = []
}

async function onImportDsChange(id: number) {
  // 先清空上一次的列,否则新数据源的列还没回来,界面上仍挂着旧源的字段
  dsCols.value = []
  if (!id) return
  try {
    // 读取映射后的字段名，同时保留文件原始表头作为列注释。
    const det = await dataSourcesApi.detail(id)
    dsCols.value = fileSourceColumns(det)
  } catch {
    // 取列失败就保持空列表,「读取并填充」按钮随之保持 disabled,不会导入空结构
    dsCols.value = []
  }
}

/** DuckDB 类型 → ClickHouse 类型 */
function ckTypeOf(duck: string): string {
  // DuckDB 类型名常带精度后缀(如 DECIMAL(18,2)),精确比全会漏判,故用子串匹配;
  // 最后的 String 兜底保证任何没认出来的类型也能生成可执行的 DDL
  const t = duck.toUpperCase()
  if (t.includes('BIGINT')) return 'Int64'
  if (t.includes('INT')) return 'Int32'
  if (t.includes('DOUBLE') || t.includes('FLOAT') || t.includes('DECIMAL')) return 'Float64'
  if (t.includes('BOOLEAN')) return 'Bool'
  if (t.includes('DATE')) return 'Date'
  if (t.includes('TIMESTAMP') || t.includes('DATETIME')) return 'DateTime'
  return 'String'
}

function onImportDs() {
  if (!dsCols.value.length) return
  // 整体覆盖而非追加:导入的目标是一张全新表的字段;orig 记下来源列名供「原字段」列对照
  columns.value = dsCols.value.map(c => ({
    name: c.name,
    type: ckTypeOf(c.type),
    nullable: false,
    defaultValue: '',
    comment: c.comment,
    orig: c.name,
  }))
  importDsVisible.value = false
  // ODS 模式:导入内容可能缺贴源固定列,导入后重新补齐(列存在则保留,不重复)
  if (isOds.value) applyOdsState()
  ElMessage.success('已读取数据源表结构,请核对字段后创建')
}

onMounted(async () => {
  // 数据源列表只服务于「从数据源导入」这个入口,取不到也不影响手动建表,因此不打扰用户
  try {
    const [all, groups] = await Promise.all([dataSourcesApi.list(), dsGroupsApi.list()])
    localDataSources.value = all.filter(d => d.type === 'csv' || d.type === 'json')
    dsGroups.value = groups as any
  } catch { /* ignore */ }
})
// 导入 DDL 期间临时置 true,用来抑制 watch(engine) 对引擎参数的清空
let suppressEngineReset = false

/** 去掉表名开头的 ods_ 前缀 */
function stripOdsName(name: string): string {
  return name.trim().replace(/^ods_+/i, '')
}
/** 最终表名:ODS 时固定加 ods_ 前缀 */
function finalTableName(): string {
  const base = stripOdsName(tableName.value)
  return isOds.value ? 'ods_' + base : base
}

// ODS 贴源表:自动追加 入库时间 + 来源 字段(固定排在最后)
const isOds = ref(false)

/** 依据当前 isOds 状态,保证列与分区与 ODS 约定一致(新增列 / 移除保留列+清键)。
 *  作为独立函数供 watch(isOds) 与每次打开对话框后调用——避免重复打开时
 *  isOds 值未变化导致 watch 不触发、ODS 固定列丢失。 */
const MDN_FIELD = 'mdn' // 贴源表手机号主键字段名(字典默认,按需求固定 mdn)
/** 贴源固定保留列名:手机号 mdn(首行) + ods_insert_at / ods_source_id(尾列) */
function odsReserved(): Set<string> {
  return new Set([MDN_FIELD, ...ODS_COLS.map((c) => c.name)])
}

function applyOdsState() {
  if (isOds.value) {
    // 1) 保证固定列存在:mdn 首行、ods 尾列。系统自动追加的行打 _odsSys 标记 ——
    //    只读(置灰)按该标记判定,而不是按名字,否则用户自己再写一个 mdn 会被一并置灰。
    if (!columns.value.some((x) => x.name.trim() === MDN_FIELD)) {
      columns.value.unshift({ name: MDN_FIELD, type: 'String', nullable: false, defaultValue: '', comment: '号码', _odsSys: true })
    }
    for (const c of ODS_COLS) {
      if (!columns.value.some((x) => x.name.trim() === c.name)) columns.value.push({ ...c, _odsSys: true })
    }
    // 2) 重排:mdn 首行 → 业务字段(用户添加) → ods 尾列
    const res = odsReserved()
    const mdn = columns.value.find((x) => x.name.trim() === MDN_FIELD)
    const tail = ODS_COLS.map((c) => columns.value.find((x) => x.name.trim() === c.name))
      .filter((x): x is ColumnRow => Boolean(x))
    const business = columns.value.filter((x) => !res.has(x.name.trim()))
    columns.value = [...(mdn ? [mdn] : []), ...business, ...tail]
    // 表名只留业务部分,ods_ 前缀统一交给 finalTableName 拼,避免用户先输过前缀而出现 ods_ods_x
    tableName.value = stripOdsName(tableName.value)
    // 分区:ODS 贴源表默认按 ods_insert_at 按日分区
    if (!partitionExpr.value.trim()) {
      partitionExpr.value = 'toYYYYMMDD(ods_insert_at)'
    }
    // 主键/排序键:贴源默认以手机号 mdn 为主键、排序键首列为 mdn(仅用户尚未手选时)
    if (!primaryKey.value.length) primaryKey.value = [MDN_FIELD]
    if (!sortingKey.value.length) sortingKey.value = [MDN_FIELD]
  } else {
    // 关闭贴源:撤掉自动追加的固定列,让开关可逆
    columns.value = columns.value.filter((x) => !odsReserved().has(x.name.trim()))
    pruneKeys()
    // 引擎参数若引用了被移除的 ODS 保留列(如 Replacing/版本列),一并清空避免生成引用已删列的 DDL
    paramSign.value = ''
    paramVersion.value = ''
    paramSumming.value = []
    // 与开启时对称地把 ods_ 前缀也摘掉,避免用户在表名里重复输入
    tableName.value = stripOdsName(tableName.value)
  }
}

/** ODS 固定保留字段是否只读:仅【系统自动追加】的 mdn / ods_insert_at / ods_source_id 不可编辑。
 *  按行标记(_odsSys)判定而非按名字:否则用户自己再写一个 mdn 会被误判成保留列一并置灰,
 *  正确行为是把「重复列名」判为不允许(见 validateGrid),而不是把两行都锁掉。 */
function isOdsRo(row: ColumnRow): boolean {
  return isOds.value && row._odsSys === true
}

watch(isOds, () => applyOdsState())

function onTableNameBlur() {
  if (isOds.value) tableName.value = stripOdsName(tableName.value)
}

const columns = ref<ColumnRow[]>([])

// 注意:GraphiteMergeTree 需要服务端配置文件参数,表格方式下无法正确生成,故不放进来(可用 DDL 方式)。
const engineOptions = ENGINE_OPTIONS
const typeOptions = TYPE_OPTIONS

const isMergeTree = computed(() => isMergeTreeOf(engine.value))

// 需要/可带引擎参数的引擎
const engineNeedsParam = computed(() => engineNeedsParamOf(engine.value))

// 引擎参数子字段(按引擎用下拉选择)
const paramSign = ref('') // Collapsing 的 Sign / VersionedCollapsing 的 Sign
const paramVersion = ref('') // VersionedCollapsing 的 Version / Replacing 的可选版本列
const paramSumming = ref<string[]>([]) // Summing 的可选汇总列

// 各引擎参数的形态不同(V 版折叠是 Sign,Version 两值,Summing 是列清单),
// 这里统一拼成 DDL 括号里的字符串,空值不参与拼接以省掉多余逗号
const engineParamValue = computed(() => {
  switch (engine.value) {
    case 'CollapsingMergeTree':
      return paramSign.value
    case 'VersionedCollapsingMergeTree':
      return [paramSign.value, paramVersion.value].filter(Boolean).join(', ')
    case 'ReplacingMergeTree':
      return paramVersion.value
    case 'SummingMergeTree':
      return paramSumming.value.join(', ')
    default:
      return ''
  }
})

// 切换引擎时清空引擎参数子字段(导入 DDL 时临时关闭,避免清掉刚填入的参数)。
// 必须用 sync flush:onImport 在同步块内 置标记→改引擎→填参数→清标记,
// 若异步触发,回调执行时标记已被清掉,刚导入的引擎参数会被误清。
watch(
  engine,
  () => {
    if (suppressEngineReset) return
    paramSign.value = ''
    paramVersion.value = ''
    paramSumming.value = []
  },
  { flush: 'sync' },
)

// 拼下拉选项前先滤掉空名,否则键/引擎参数里会出现空选项,生成非法 DDL
const colNameOptions = computed(() => columns.value.map((c) => c.name.trim()).filter(Boolean))

/** 重复的字段名集合:出现两次及以上的名字(用于把冲突行标红,提示"不允许") */
const dupNames = computed(() => {
  const seen = new Set<string>()
  const dup = new Set<string>()
  for (const c of columns.value) {
    const n = c.name.trim()
    if (!n) continue
    if (seen.has(n)) dup.add(n)
    seen.add(n)
  }
  return dup
})
function isDupName(name: string): boolean {
  return dupNames.value.has(name.trim())
}
/** 行样式:字段名重复的整行标红 */
function dupRowClass({ row }: { row: ColumnRow }): string {
  return isDupName(row.name) ? 'dup-column-row' : ''
}
/** 是否存在从数据源/DDL 导入的来源字段(有则显示“原字段”列) */
const hasOrig = computed(() => columns.value.some((c) => !!c.orig))

// 分区键候选:纯字段 + 各函数应用(如 toYYYYMMDD(created))
const partitionOptions = computed(() => partitionOptionsOf(tableState()))

// 重置对话框状态
watch(
  () => props.modelValue,
  (v) => {
    // 只在打开时重置:每次都从干净状态起步,不会把上次没提交的表格误建到别的库里
    if (v) {
      suppressEngineReset = false // 清掉可能残留的导入标记,避免影响后续手动切换引擎
      mode.value = 'grid'
      tableName.value = ''
      tableRemark.value = ''
      engine.value = 'MergeTree'
      partitionExpr.value = ''
      sortingKey.value = []
      primaryKey.value = []
      paramSign.value = ''
      paramVersion.value = ''
      paramSumming.value = []
      isOds.value = !!props.asOds
      // 默认留一行空字段,打开即可直接输入,不用先点「添加字段」
      columns.value = [{ name: '', type: 'String', nullable: false, defaultValue: '', comment: '' }]
      ddlText.value = ''
      previewOpen.value = false
      // 无论 isOds 是否变化都显式同步 ODS 列/分区(修复重复打开时 watch(isOds) 不触发导致固定列丢失)
      applyOdsState()
    }
  },
)

// 切换模式时,DDL 方式预填当前表格生成的 DDL
watch(mode, (m) => {
  // 只在用户还没手写过 DDL 时预填,免得表格→DDL→表格来回切换把手工改动冲掉
  if (m === 'ddl' && !ddlText.value.trim()) {
    ddlText.value = generatedDDL.value
  }
})

/** 组装纯函数所需的 TableState */
function tableState(): TableState {
  return {
    db: props.db,
    tableName: finalTableName(),
    tableRemark: tableRemark.value,
    engine: engine.value,
    partitionExpr: partitionExpr.value,
    sortingKey: sortingKey.value,
    primaryKey: primaryKey.value,
    columns: columns.value,
    paramSign: paramSign.value,
    paramVersion: paramVersion.value,
    paramSumming: paramSumming.value,
  }
}

function addColumn() {
  // 新业务字段追加在 mdn 之后、ods 尾列(ods_insert_at/ods_source_id)之前;mdn 固定首行不动
  const tailIdx = columns.value.findIndex((c) => ODS_COLS.some((o) => o.name === c.name.trim()))
  const insertAt = tailIdx >= 0 ? tailIdx : columns.value.length
  columns.value.splice(insertAt, 0, { name: '', type: 'String', nullable: false, defaultValue: '', comment: '' })
}

function removeColumn(index: number) {
  columns.value.splice(index, 1)
  pruneKeys()
}

// 删列后同步清掉排序键/主键/分区里的失效引用,否则生成的 DDL 会引用不存在的列被服务端拒绝
function pruneKeys() {
  const names = new Set(colNameOptions.value)
  sortingKey.value = sortingKey.value.filter((k) => names.has(k))
  primaryKey.value = primaryKey.value.filter((k) => names.has(k))
  // 分区表达式若指向已删除的裸字段,一并清掉
  if (partitionExpr.value && !partitionOptions.value.includes(partitionExpr.value)) {
    partitionExpr.value = ''
  }
}

// 由表格配置生成 DDL(纯函数)
const generatedDDL = computed(() => generateDDL(tableState()))

// DDL 预览高亮
const highlightedDDL = computed(() => highlightDDL(generatedDDL.value))

function validateGrid(): string | null {
  // 字段名重复优先拦:用户再写一个 mdn / 任意重名列应当「不允许」,
  // 且提示要盖过下面 ODS 的"至少一个业务字段"(否则重名 mdn 会误报成缺业务字段)
  const dup = duplicateColumnError(columns.value)
  if (dup) return dup
  // ODS 表:必须填写 ods_ 之后的具体表名,避免建出仅叫 ods_ 的表
  if (isOds.value && !stripOdsName(tableName.value)) {
    return 'ODS 贴源表也需填写具体表名(如 orders → ods_orders)'
  }
  // ODS 表:除固定保留列(mdn / ods_insert_at / ods_source_id)外,必须再填写至少一个业务字段
  if (isOds.value) {
    const business = columns.value.filter((c) => !odsReserved().has(c.name.trim()))
    // 名字或类型只填一半的行不算有效业务字段:在前端拦下比等服务端回语法错更省事
    const filled = business.filter((c) => c.name.trim() && c.type.trim())
    if (!filled.length) {
      return '请至少填写一个业务字段(除手机号 mdn 与 ods_insert_at / ods_source_id 外)'
    }
  }
  return validateGridState(tableState())
}

function onImport() {
  // 解析失败(不是 CREATE TABLE 等)直接抛错,由下面的 catch 提示,当前表格内容保持原样
  try {
    const parsed = parseCreateDDL(importDdl.value)
    suppressEngineReset = true
    engine.value = parsed.engine
    const p = parseEngineParams(parsed.engine, parsed.engineParam)
    paramSign.value = p.paramSign
    paramVersion.value = p.paramVersion
    paramSumming.value = p.paramSumming
    suppressEngineReset = false
    tableName.value = isOds.value ? stripOdsName(parsed.tableName) : parsed.tableName
    partitionExpr.value = parsed.partitionExpr
    // 键里可能引用了未在字段列表中声明的列,过滤后再回填,防止后续生成 DDL 报错
    const colNames = new Set(parsed.columns.map((c) => c.name))
    sortingKey.value = parsed.sortingKey.filter((c) => colNames.has(c))
    primaryKey.value = parsed.primaryKey.filter((c) => colNames.has(c))
    columns.value = parsed.columns
    importVisible.value = false
    // ODS 模式:导入 DDL 可能缺贴源固定列/默认分区,导入后补齐
    if (isOds.value) applyOdsState()
    ElMessage.success('已识别,请核对字段与键后创建')
  } catch (e) {
    // 失败时保留弹窗和用户粘贴的原文,便于对着错误改一改再识别
    ElMessage.warning(e instanceof Error ? e.message : 'DDL 识别失败')
  }
}

async function onCreate() {
  let ddl = ''
  if (mode.value === 'grid') {
    const err = validateGrid()
    if (err) {
      ElMessage.warning(err)
      return
    }
    ddl = generatedDDL.value
  } else {
    ddl = ddlText.value.trim()
    if (!ddl) {
      ElMessage.warning('DDL 不能为空')
      return
    }
  }
  // 打开 loading 兼作防重提交:建表不可回滚,重复点击只会多报一次「表已存在」
  if (!(await confirmAction('创建表', `确认在「${props.db}」创建表「${finalTableName()}」？`, '确认创建'))) return
  creating.value = true
  try {
    await workspaceApi.createTable(props.connId, props.db, ddl)
    ElMessage.success('建表成功')
    emit('created')
    emit('update:modelValue', false)
  } catch {
    /* 拦截器已提示 */
    // 失败不关弹窗:用户改个表名就能直接重试,不必重填整张表
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.mode-switch {
  margin-bottom: 16px;
}
.grid-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.grid-toolbar-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.ods-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: rgba(234, 179, 8, 0.06);
  border: 1px dashed rgba(234, 179, 8, 0.35);
  border-radius: 8px;
}
.ods-fixed {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ods-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--brand-strong);
}
.orig-cell {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.ds-col-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.import-editor {
  height: 320px;
}
.table-settings,
.key-settings {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.table-remark {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.table-remark .setting-label {
  flex: none;
}
.tbl-name-input {
  display: flex;
  align-items: center;
  gap: 4px;
}
.ods-fix {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--brand-strong);
  background: rgba(234,179,8,0.10);
  padding: 0 6px;
  line-height: 26px;
  border-radius: 4px;
  border: 1px solid rgba(234,179,8,0.3);
  flex: none;
}
.setting-item {
  flex: 1;
  min-width: 0;
}
.setting-label {
  display: block;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.param-pair {
  display: flex;
  gap: 8px;
}
.cols-table {
  margin-bottom: 8px;
}
.add-col {
  margin-bottom: 12px;
}
.ddl-preview {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #0d1117;
  overflow: hidden;
}
.preview-head {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  padding: 8px 12px;
  font-size: 12px;
  letter-spacing: 0.06em;
  color: var(--el-text-color-secondary);
  background: var(--fill-soft);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.preview-arrow {
  flex: none;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.preview-copy {
  margin-left: auto;
  letter-spacing: 0;
}
.ddl-preview pre {
  margin: 0;
  padding: 12px;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  color: #c9d1d9;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}
.ddl-hl .hl-keyword {
  color: #ff7b72;
  font-weight: 600;
}
.ddl-hl .hl-type {
  color: #79c0ff;
}
.ddl-hl .hl-string {
  color: #a5d6ff;
}
.ddl-hl .hl-number {
  color: #f2cc60;
}
.ddl-hl .hl-comment {
  color: #8b949e;
  font-style: italic;
}
.ddl-hl .hl-ident {
  color: #d2a8ff;
}
.ddl-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.example {
  margin: 0 0 10px;
  padding: 10px 12px;
  background: var(--fill-soft);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}
.ddl-editor {
  height: 300px;
}
/* 字段名重复:整行淡红底 + 输入框红框,一眼看出是哪一行冲突(而不是把两行都置灰) */
:deep(.cols-table .dup-column-row) td {
  background: var(--el-color-danger-light-9, #fef0f0);
}
.dup-name :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
</style>
