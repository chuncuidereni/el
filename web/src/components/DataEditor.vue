<!--
  DataEditor.vue

  职责：数据编辑组件：管理结果集单元格编辑、变更跟踪和保存/回滚交互。
  边界：按连接、库、表和筛选分页加载数据；自动查询定时器在新输入前清理，编辑草稿本地保存，保存或回滚成功后再刷新结果。
-->

<template>
  <div class="data-editor">
    <!-- 查询条件(分组构建) -->
    <div class="query-bar">
      <div class="query-head">
        <span class="query-title qc-toggle" @click="filterOpen = !filterOpen" :title="filterOpen ? '收起条件' : '展开条件'">
          <span class="qc-arrow" :class="{ open: filterOpen }">▸</span>
          查询条件
        </span>
        <span class="qc-label">显示列</span>
        <el-select
          v-model="selectedCols"
          multiple
          collapse-tags
          collapse-tags-tooltip
          clearable
          filterable
          placeholder="全部列"
          class="f-show-cols"
          @visible-change="onColsVisibleChange"
        >
          <el-option v-for="c in allColumns" :key="c" :label="c" :value="c">
            <span class="opt-name">{{ c }}</span>
            <span v-if="colComments[c]" class="opt-comment">{{ colComments[c] }}</span>
          </el-option>
        </el-select>
        <!-- 面板收起后仍要提示「已应用 N 个条件」:否则用户看到的是被过滤过的数据却以为在查全表 -->
        <div class="query-actions">
          <el-tag v-if="appliedCount" size="small" type="warning" effect="plain" class="applied-tag">
            已应用 {{ appliedCount }} 个条件
          </el-tag>
          <el-button v-if="!filterOpen && appliedCount" size="small" @click="filterOpen = true">查看条件</el-button>
          <el-button size="small" type="primary" :icon="Search" @click="onFilterApply">查询</el-button>
          <el-button size="small" @click="onFilterReset">重置</el-button>
        </div>
      </div>

      <div class="query-body" v-show="filterOpen">
        <FilterBuilder
          :model-value="rootFilter"
          :all-columns="allColumns"
          :col-comments="colComments"
          :col-types="colTypes"
          is-root
          @update:model-value="(v) => (rootFilter = v)"
          @run="onFilterApply"
        />
      </div>
    </div>

    <div class="editor-toolbar">
      <!-- 只读:目标表由工作流生成,改了下次摊平也会被覆盖,所以不给增删改 -->
      <template v-if="!readonly">
        <el-button size="small" type="primary" :icon="Plus" @click="addRow">新增行</el-button>
        <el-button size="small" type="danger" plain :disabled="!hasSelection" @click="stageDelete">删除选中</el-button>
        <el-button size="small" :disabled="!dirty" @click="reload">放弃修改</el-button>
        <el-button size="small" type="success" :disabled="!dirty || saving" :loading="saving" @click="commit">
          提交修改
        </el-button>
        <el-divider direction="vertical" />
      </template>
      <el-button size="small" :icon="Download" @click="exportVisible = true">导出 CSV</el-button>
      <!-- 给出增/改/删的条数:切页或关闭前一眼能看出还有多少暂存改动没提交 -->
      <span v-if="dirty && !readonly" class="dirty-hint">新增 {{ stats.inserts }} · 修改 {{ stats.updates }} · 删除 {{ stats.deletes }}</span>
      <span v-if="!canEditRow && !readonly" class="no-key-hint">该表无主键/排序键,仅支持新增行</span>
    </div>

    <!-- 导出 CSV 选项弹窗 -->
    <el-dialog v-model="exportVisible" title="导出 CSV" width="520px" :close-on-click-modal="false" :close-on-press-escape="!exporting">
      <el-form label-width="90px" label-position="left">
        <el-form-item label="导出列">
          <el-select v-model="exportCols" multiple filterable collapse-tags collapse-tags-tooltip clearable placeholder="全部列" style="width: 100%" @change="onExportColsChange">
            <el-option v-for="c in allColumns" :key="c" :label="c" :value="c">
              <span class="opt-name">{{ c }}</span>
              <span v-if="colComments[c]" class="opt-comment">{{ colComments[c] }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <!-- 列名别名(允许重新设置各列名) -->
        <el-form-item v-if="exportCols.length" label="导出列名">
          <div class="export-col-names">
            <div v-for="(c, i) in exportCols" :key="c" class="export-col-name-row">
              <span class="export-col-orig" :title="c">{{ c }}</span>
              <el-input v-model="exportHeaderNames[i]" size="small" placeholder="导出列名(可改)" @input="onHeaderNameInput(i)" />
            </div>
          </div>
        </el-form-item>

        <el-form-item label="表头">
          <el-switch v-model="exportHeader" />
          <span class="export-hint">第一行输出列名</span>
        </el-form-item>
        <el-form-item label="分隔符">
          <el-radio-group v-model="exportDelimiter">
            <el-radio value=",">逗号 ,</el-radio>
            <el-radio value="\t">制表符 Tab</el-radio>
            <el-radio value=";">分号 ;</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="导出行数">
          <el-input-number v-model="exportLimit" :min="0" :max="MAX_EXPORT_ROWS" :step="1000" />
          <span class="export-hint">0 表示全部(可能很大,谨慎)</span>
        </el-form-item>
        <el-form-item v-if="appliedCount" label="过滤条件">
          <el-tag size="small" type="warning" effect="plain">已应用 {{ appliedCount }} 个条件,导出将遵循</el-tag>
        </el-form-item>
        <el-form-item label="文件名">
          <span class="export-filename">{{ exportFilename }}</span>
        </el-form-item>
      </el-form>

      <!-- 导出进度 -->
      <div v-if="exporting" class="export-progress">
        <el-progress :percentage="exportProgress" :status="exportProgress >= 100 ? 'success' : undefined" :stroke-width="10" />
        <div class="export-progress-text">{{ exportStatusText }}</div>
      </div>

      <template #footer>
        <el-button :disabled="exporting" @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="doExport">
          {{ exporting ? '导出中…' : '确认导出' }}
        </el-button>
      </template>
    </el-dialog>

    <div class="table-wrap">
      <el-table
        :data="rows"
        border
        size="small"
        height="100%"
        class="edit-table"
        v-loading="loading"
        :row-class-name="rowClassName"
      >
        <el-table-column v-if="!readonly" width="38" align="center">
          <!-- 已标删的行禁用勾选:重复标记没有意义,留着勾选态还会让「删除选中」的行为变得含糊 -->
          <template #default="{ row }">
            <el-checkbox v-model="row._selected" :disabled="row._status === 'deleted'" />
          </template>
        </el-table-column>
        <el-table-column type="index" label="序号" width="62" align="center" :index="rowIndex" class-name="idx-col" />
        <el-table-column v-for="col in columns" :key="col" min-width="90">
          <template #header>
            <div class="col-head" @click.stop="onHeadSort(col, $event)">
              <span v-if="colComments[col]" class="col-head-comment" :title="colComments[col]">{{ colComments[col] }}</span>
              <span class="col-head-line">
                <span class="col-head-name" :class="{ 'sort-active': !!sortState(col) }" :title="col">{{ col }}</span>
                <span v-if="sortOrderNo(col) > 1" class="sort-no">{{ sortOrderNo(col) }}</span>
                <span class="sort-arrows">
                  <button type="button" :class="{ active: sortState(col) === 'asc' }" :title="`${col} 升序`" :aria-label="`${col} 升序`" @click.stop="onArrowSort(col, false, $event)">▲</button>
                  <button type="button" :class="{ active: sortState(col) === 'desc' }" :title="`${col} 降序`" :aria-label="`${col} 降序`" @click.stop="onArrowSort(col, true, $event)">▼</button>
                </span>
              </span>
            </div>
          </template>
          <template #default="{ row, $index }">
            <!-- 同一时刻只开一个编辑框:editingCell 用「行下标 + 列名」命中,点到别处即自动关闭上一个 -->
            <el-input
              v-if="isEditing($index, col)"
              v-model="row.v[col]"
              size="small"
              autofocus
              :placeholder="row._status === 'new' ? '(默认)' : ''"
              @input="onCellChange(row)"
              @blur="stopEdit(row)"
              @keyup.enter="stopEdit(row)"
            />
            <!-- 非编辑态渲染纯文本;空值把 title 置空,避免悬停弹出无意义的 "null" -->
            <span
              v-else
              class="cell-text"
              :class="{ 'cell-del': row._status === 'deleted', 'cell-editable': isEditable(row, col) }"
              :title="row.v[col] == null ? '' : String(row.v[col])"
              @click="startEdit($index, col)"
            >{{ row.v[col] }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 分页:切页/改每页条数都会先确认未提交的改动(见 onPageChange / onSizeChange) -->
    <div class="pager-bar">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[20, 50, 100, 200, 500]"
        :pager-count="7"
        layout="total, sizes, prev, pager, next, jumper"
        background
        small
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Search } from '@element-plus/icons-vue'
import { workspaceApi } from '@/api'
import { MAX_EXPORT_ROWS } from '@/constants/export'
import { cleanFilter, countConditions, sanitizeFilterColumns, type FilterNode } from '@/utils/filter'
import type { RowUpdate } from '@/types'
import { confirmAction, confirmDestructive } from '@/utils/confirm'
import FilterBuilder from '@/components/FilterBuilder.vue'
import { privateStorage } from '@/utils/privateStorage'

const queryStorage = privateStorage()

const props = defineProps<{
  connId: number
  db: string
  table: string
  /**
   * 只读模式(工作流产出的目标表用)。
   *
   * 目标表是工作流的产物:改了也会被下次摊平覆盖,所以不给增删改 ——
   * 隐藏「新增行/删除选中/放弃修改/提交修改」,并让 isEditable 恒为 false
   * (单元格点击也进不了编辑态)。
   */
  readonly?: boolean
}>()

interface GridRow {
  _status: 'original' | 'new' | 'modified' | 'deleted'
  _key: Record<string, any>
  _orig: Record<string, any>
  _selected: boolean
  v: Record<string, any>
}

const loading = ref(false)
const saving = ref(false)
const columns = ref<string[]>([])
const keyColumns = ref<string[]>([])
const rows = ref<GridRow[]>([])
/** 重载代次:用于丢弃乱序返回的旧响应,避免旧结果覆盖新一次查询 */
let reloadSeq = 0

// ---- 分页 ----
const page = ref(1)
const pageSize = ref(100)
const total = ref(0)
let lastPage = 1 // 导航成功前记住上一页,取消时回退
let lastSize = 100
// 总页数至少为 1:0 条结果时页码也应停在第 1 页,否则越界判断会误触发回退
const totalPages = computed(() => (pageSize.value ? Math.max(1, Math.ceil(total.value / pageSize.value)) : 1))

/** 行序号:跨分页的全局序号 */
function rowIndex(index: number): number {
  return (page.value - 1) * pageSize.value + index + 1
}

// ---- 查询条件(分组构建) ----
const rootFilter = ref<FilterNode>({ logic: 'and', children: [] })
const appliedFilter = ref<FilterNode>({ logic: 'and', children: [] })
/** 查询条件面板展开/收起(默认收起,列表优先) */
const filterOpen = ref(false)
const appliedCount = computed(() => countConditions(appliedFilter.value))

/** 应用查询条件:清理空条件/空分组后提交;回到第 1 页 */
async function onFilterApply() {
  // 有未提交的单元格修改时先确认,避免静默丢弃
  if (!(await confirmDiscard())) return
  // cleanFilter 剔除空条件/空分组:用户点了「添加条件」却没填很常见,不清理会拼出非法 SQL
  appliedFilter.value = cleanFilter(rootFilter.value) ?? { logic: 'and', children: [] }
  // 条件变了必须回到第 1 页,否则停在第 3 页可能正好落在新结果集之外
  page.value = 1
  lastPage = 1
  persistQuery()
  reload()
}

/** 重置查询条件与显示列 */
async function onFilterReset() {
  if (!(await confirmDiscard())) return
  suppressNextAuto = true // 跳过重置后的一次自动查询(下面会立即 reload)
  // 编辑态与已应用态都要清:只清一份会让面板里残留旧条件,看起来像没重置成功
  rootFilter.value = { logic: 'and', children: [] }
  appliedFilter.value = { logic: 'and', children: [] }
  // 显示列也属于查询状态，重置后恢复为全部列并同步写回本地查询配置。
  selectedCols.value = []
  page.value = 1
  lastPage = 1
  persistQuery()
  reload()
}

// ---- 条件变化自动查询(防抖 400ms) ----
let autoQueryReady = false // 首次挂载完成前不触发,避免恢复配置时重复查询
let suppressNextAuto = false // 跳过下一次自动查询(手动已触发时)
let autoQueryTimer: number | undefined
function debouncedAutoQuery() {
  if (suppressNextAuto) {
    // 一次性标志:手动查询/重置已经发过请求了,这里再触发一次就是重复查询
    suppressNextAuto = false
    return
  }
  window.clearTimeout(autoQueryTimer)
  // 触发时再检查脏状态,防止 400ms 内用户开始编辑导致编辑被静默丢弃
  autoQueryTimer = window.setTimeout(() => {
    // 400ms 内用户可能已经开始改单元格,此时查询会弹确认框打断输入,干脆放弃这次自动查询
    if (dirty.value) return
    onFilterApply()
  }, 400)
}
watch(
  rootFilter,
  () => {
    if (autoQueryReady) debouncedAutoQuery()
  },
  { deep: true },
)

// ---- 指定列查询(列式数据库只读选定列,减少 IO) ----
const allColumns = ref<string[]>([]) // 表全部列(字段下拉/列选择用)
const selectedCols = ref<string[]>([]) // 指定查询列,空 = 全部列

// ---- 排序(列头点击;Ctrl/⌘+点击累积多列) ----
const sortOrders = ref<{ column: string; desc: boolean }[]>([])

type SortDir = 'asc' | 'desc' | null
function sortState(col: string): SortDir {
  const o = sortOrders.value.find((x) => x.column === col)
  return o ? (o.desc ? 'desc' : 'asc') : null
}
function sortOrderNo(col: string): number {
  return sortOrders.value.findIndex((x) => x.column === col) + 1
}

/** 列头点击排序:默认单列;Ctrl/⌘+点击 追加为多列(优先级 1,2,3…) */
async function onHeadSort(col: string, e: MouseEvent) {
  if (!(await confirmDiscard())) return // 有未提交修改需先确认,避免静默丢失
  // Ctrl/⌘/Shift 任一按下都算多列模式;单列模式下点其他列会直接替换掉原有排序
  const multi = e.ctrlKey || e.metaKey || e.shiftKey
  const idx = sortOrders.value.findIndex((x) => x.column === col)
  if (!multi) {
    // 单列:仅该列循环 升 → 降 → 取消
    if (idx === -1) {
      sortOrders.value = [{ column: col, desc: false }]
    } else {
      const cur = sortOrders.value[idx]
      if (cur.desc) sortOrders.value = []
      else sortOrders.value = [{ column: col, desc: true }]
    }
  } else {
    // 多列:已有则 升→降→移除;否则追加升序
    if (idx === -1) {
      sortOrders.value = [...sortOrders.value, { column: col, desc: false }]
    } else {
      const cur = sortOrders.value[idx]
      if (!cur.desc) {
        sortOrders.value = sortOrders.value.map((x, i) => (i === idx ? { ...x, desc: true } : x))
      } else {
        sortOrders.value = sortOrders.value.filter((_, i) => i !== idx)
      }
    }
  }
  // 排序变了,原页码对应的数据顺序已无意义,回到第 1 页
  page.value = 1
  reload()
}

/** 小箭头指定升降序；修饰键允许继续追加其它排序列。 */
async function onArrowSort(col: string, desc: boolean, e: MouseEvent) {
  if (!(await confirmDiscard())) return
  const order = { column: col, desc }
  sortOrders.value = e.ctrlKey || e.metaKey || e.shiftKey
    ? [...sortOrders.value.filter((x) => x.column !== col), order]
    : [order]
  page.value = 1
  reload()
}

// ---- CSV 导出 ----
const exportVisible = ref(false)
const exportCols = ref<string[]>([])
const exportHeaderNames = ref<string[]>([])
const exportHeader = ref(true)
const exportDelimiter = ref(',')
const exportLimit = ref(0)
const exporting = ref(false)
const exportProgress = ref(0)
const exportStatusText = ref('')

/** 导出的文件名:表名_YYYYMMDD_HHMMSS.csv */
const exportFilename = computed(() => {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const ts = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `${props.table}_${ts}.csv`
})

/** 导出列变化:同步维护列名别名数组(按当前选中顺序,保留已填别名) */
const exportNameMap = ref<Record<string, string>>({}) // 列名 → 别名(跨选择保留)
function onExportColsChange() {
  // 从映射重建别名:取消勾选再勾回来时,之前填过的别名不会丢
  exportHeaderNames.value = exportCols.value.map((c) => exportNameMap.value[c] ?? c)
}

/** 别名输入变化:同步到映射,避免重选列丢失别名 */
function onHeaderNameInput(i: number) {
  const col = exportCols.value[i]
  if (col) exportNameMap.value[col] = exportHeaderNames.value[i]
}

async function doExport() {
  exporting.value = true
  exportProgress.value = 5
  exportStatusText.value = '正在查询数据…'
  try {
    // 阶段1:查询 + 生成 CSV(后端一次返回)
    exportProgress.value = 30
    exportStatusText.value = '正在生成 CSV…'
    const blob = await workspaceApi.exportCsv(props.connId, props.db, props.table, {
      columns: exportCols.value,
      // 后端按位置接收别名,所以按当前选中列的顺序展开;留空串表示沿用原列名
      headerNames: exportCols.value.map((_, i) => exportHeaderNames.value[i] ?? ''),
      filter: appliedFilter.value,
      withHeader: exportHeader.value,
      delimiter: exportDelimiter.value,
      // 0 是「全部」的界面表达,转成 undefined 让后端干脆不加 LIMIT
      limit: exportLimit.value || undefined,
      orders: sortOrders.value,
    })
    exportProgress.value = 80
    exportStatusText.value = '正在下载…'
    // 阶段2:触发下载(服务端流式返回,已带 UTF-8 BOM)
    downloadBlob(blob, exportFilename.value)
    exportProgress.value = 100
    exportStatusText.value = '完成'
    ElMessage.success('导出完成')
    // 延迟关闭,让用户看到完成进度
    setTimeout(() => {
      exportVisible.value = false
      exportProgress.value = 0
      exportStatusText.value = ''
    }, 800)
  } catch {
    /* 拦截器已提示 */
    // 进度与文案必须复位,否则下次打开弹窗还停在上一次的失败进度上
    exportProgress.value = 0
    exportStatusText.value = ''
  } finally {
    exporting.value = false
  }
}

/**
 * 触发浏览器下载 CSV 文件；UTF-8 BOM 已由服务端写入响应流，这里不再重复添加。
 */
function downloadBlob(blob: Blob, filename: string) {
  // Blob URL 点击后立刻释放:同一页面反复导出否则会把文件内容一直留在内存里
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}


let colsAtOpen = '' // 下拉打开时的列快照,用于判断是否变化
async function onColsVisibleChange(visible: boolean) {
  if (visible) {
    // 打开时先快照:el-select 的 change 是每勾一次就触发,只有 visible=false 才代表「选完了」
    colsAtOpen = JSON.stringify(selectedCols.value)
    return
  }
  if (JSON.stringify(selectedCols.value) === colsAtOpen) return
  // 下拉关闭且列有变化才重新查询一次,避免每勾选一个就发一次请求
  if (dirty.value && !(await confirmDiscard())) {
    // 取消:回退到打开前的列选择,不重新加载
    try {
      selectedCols.value = JSON.parse(colsAtOpen)
    } catch {
      // 快照解析失败(理论不会发生)时退回全列,总好过停在一个残缺的列集合上
      selectedCols.value = []
    }
    return
  }
  page.value = 1
  lastPage = 1
  persistQuery()
  reload()
}

// ---- 查询配置按当前账号及连接/库/表隔离，刷新后可恢复 ----
const STORAGE_PREFIX = 'el2ck:query:'
function storageKey(): string {
  return `${STORAGE_PREFIX}${props.connId}:${props.db}:${props.table}`
}
function persistQuery() {
  // 存 localStorage 只是「刷新不丢配置」的兜底,写失败(隐私模式/配额满)不该影响主流程
  try {
    queryStorage.setItem(
      storageKey(),
      JSON.stringify({ cols: selectedCols.value, filter: rootFilter.value }),
    )
  } catch {
    /* 忽略(隐私模式等场景) */
  }
}
function restoreQuery() {
  try {
    const raw = queryStorage.getItem(storageKey())
    if (!raw) return
    const saved = JSON.parse(raw)
    if (Array.isArray(saved.cols)) selectedCols.value = saved.cols
    if (saved.filter && typeof saved.filter === 'object') rootFilter.value = saved.filter
  } catch {
    /* 忽略 */
  }
}

// 字段备注 + 全字段列表 + 列类型(来自 DESCRIBE)
const colComments = ref<Record<string, string>>({})
const colTypes = ref<Record<string, string>>({})
async function loadComments() {
  try {
    const res = await workspaceApi.schema(props.connId, props.db, props.table)
    const map: Record<string, string> = {}
    const types: Record<string, string> = {}
    const cols: string[] = []
    res.rows.forEach((r) => {
      const name = String(r[0] ?? '')
      if (!name) return
      cols.push(name)
      types[name] = String(r[1] ?? '')
      // DESCRIBE 的第 5 列才是字段备注;列数不足的驱动直接按空串处理,避免读到 undefined
      const comment = r.length > 4 ? String(r[4] ?? '') : ''
      if (comment) map[name] = comment
    })
    colComments.value = map
    colTypes.value = types
    allColumns.value = cols
    // 表结构可能已变化,过滤掉已不存在的列,避免查询报「未知字段」
    if (selectedCols.value.length) {
      // Set 换来 O(1) 命中:列多时逐项 includes 会退化成平方复杂度
      const valid = new Set(cols)
      selectedCols.value = selectedCols.value.filter((c) => valid.has(c))
    }
  } catch {
    /* 拦截器已提示,忽略 */
  }
}

/** 有无主键/排序键,决定能否定位更新/删除;键列必须都在显示列中,否则无法定位行 */
const canEditRow = computed(
  () => keyColumns.value.length > 0 && keyColumns.value.every((c) => columns.value.includes(c)),
)
// 只统计「被勾选且未标删」的行:已标删的行不该再让「删除选中」按钮保持可点
const hasSelection = computed(() => rows.value.some((r) => r._selected && r._status !== 'deleted'))
// 脏检查只看 _status:onCellChange 已保证「改回原值」会退回 original,不必在此重算
const dirty = computed(() => rows.value.some((r) => r._status !== 'original'))
const stats = computed(() => {
  let inserts = 0
  let updates = 0
  let deletes = 0
  for (const r of rows.value) {
    if (r._status === 'new') inserts++
    else if (r._status === 'modified') updates++
    else if (r._status === 'deleted') deletes++
  }
  return { inserts, updates, deletes }
})

function fmt(v: any): string {
  // 统一成字符串比较:后端返回的每列都是文本,保留原始类型会让后续脏检查出现 1 !== '1'
  if (v === null || v === undefined) return ''
  // 表达式/数组列会返回对象,JSON 化以免展示成 [object Object]
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

async function loadKeys() {
  // 主键优先,没有主键才退到排序键——MergeTree 里排序键组合同样能唯一定位一行
  try {
    const k = await workspaceApi.keys(props.connId, props.db, props.table)
    keyColumns.value = k.primaryKey.length ? k.primaryKey : k.sortingKey
  } catch {
    // 探测失败就置空:宁可整表退化成只读,也不能拿错的键去 UPDATE/DELETE
    keyColumns.value = []
  }
}

async function reload() {
  editingCell.value = null // 数据刷新后清空正在编辑的单元格
  loading.value = true
  // 代次自增并记下本次 seq:响应回来时若 reloadSeq 已变,说明后面又发过请求,本次结果作废
  const seq = ++reloadSeq
  try {
    const res = await workspaceApi.data(
      props.connId,
      props.db,
      props.table,
      (page.value - 1) * pageSize.value,
      pageSize.value,
      appliedFilter.value,
      selectedCols.value,
      true,
      sortOrders.value,
    )
    if (seq !== reloadSeq) return // 已有更新的查询,丢弃本次过期结果
    columns.value = res.columns
    total.value = res.total
    rows.value = res.rows.map((r) => {
      const v: Record<string, any> = {}
      // 后端按列顺序返回数组,这里还原成「列名 → 文本」:后续比较、提交都以这份映射为准
      res.columns.forEach((c, i) => {
        v[c] = fmt(r[i])
      })
      const key: Record<string, any> = {}
      // 键值可能因列裁剪而不在 v 里;空串视同无值,否则会拼出 key = '' 的假定位条件
      keyColumns.value.forEach((c) => {
        if (v[c] !== undefined && v[c] !== '') key[c] = v[c]
      })
      // _orig 必须是浅拷贝出的新对象:与 v 共享引用的话脏检查会永远相等
      return { _status: 'original', _key: key, _orig: { ...v }, _selected: false, v }
    })
    // 删除数据后总页数可能减少,当前页越界则回退到最后一页
    if (page.value > 1 && page.value > totalPages.value) {
      page.value = Math.max(1, totalPages.value)
      lastPage = page.value
      // 本次响应是空页,直接渲染会显示成「无数据」;递归重新取最后一页的真实数据
      await reload()
      return
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    // 仅当自己仍是最新一次请求时才关 loading,否则会把后发起请求的加载态提前关掉
    if (seq === reloadSeq) loading.value = false
  }
}

/** 有未提交修改时切换分页需确认,否则静默放行 */
async function confirmDiscard(): Promise<boolean> {
  if (!dirty.value) return true
  try {
    await ElMessageBox.confirm('当前页有未提交的修改,切换分页将丢失这些修改。是否继续?', '放弃修改', {
      type: 'warning',
      confirmButtonText: '继续切换',
      cancelButtonText: '取消',
    })
    return true
  } catch {
    return false
  }
}

async function onPageChange(p: number) {
  // 注意:v-model 已把 page 更新为 p,这里用 lastPage(上一次页码)判断是否有变化
  if (p === lastPage) return
  if (!(await confirmDiscard())) {
    page.value = lastPage // 取消则回退到切换前的页码
    return
  }
  lastPage = p
  // lastPage 既是「取消时回退」的目标,也是下一次比较的基准,确认通过后才推进
  reload()
}

async function onSizeChange(s: number) {
  if (s === lastSize) return
  if (!(await confirmDiscard())) {
    pageSize.value = lastSize // 取消则回退每页条数
    page.value = lastPage
    return
  }
  // 每页条数变了,旧页码没有可比性,页码锚点一并重置
  lastSize = s
  lastPage = 1
  page.value = 1
  pageSize.value = s
  reload()
}

function addRow() {
  const v: Record<string, any> = {}
  // 所有列先置空串:提交时按「非空才发送」过滤,等于把没填的列交给后端默认值
  columns.value.forEach((c) => {
    v[c] = ''
  })
  // _key/_orig 留空:新增行走 INSERT 分支,不需要定位键,也没有「原值」可回滚
  rows.value.push({ _status: 'new', _key: {}, _orig: {}, _selected: false, v })
}

function isEditable(row: GridRow, col: string): boolean {
  // 只读模式:单元格点击也进不了编辑态(所有编辑都从这里过)
  if (props.readonly) return false
  // 已标删的行不允许再改:提交时它整行都不会出现在 updates 里,改了也是白改
  if (row._status === 'deleted') return false
  if (row._status === 'new') return true
  // 没有键就没法定位到具体行,只能放行新增,不允许改/删
  if (!canEditRow.value) return false
  // 键列不能改:UPDATE 用的是加载时记下的键值,改了键就找不到原行
  return !keyColumns.value.includes(col) // 已有行的键列只读
}

/** 点击单元格才显示编辑框:记录当前正在编辑的行/列 */
const editingCell = ref<{ r: number; c: string } | null>(null)
function isEditing(i: number, col: string): boolean {
  return editingCell.value?.r === i && editingCell.value?.c === col
}
function startEdit(i: number, col: string) {
  const row = rows.value[i]
  // 不可编辑的格子不记录编辑态:点击没有任何反馈,也不该把上一个编辑框关掉
  if (!row || !isEditable(row, col)) return
  editingCell.value = { r: i, c: col }
}
function stopEdit(row: GridRow) {
  // 失焦与回车都走这里;v-model 在 blur 前已同步,补算一次状态即可
  onCellChange(row) // 收尾同步「已修改」标记
  editingCell.value = null
}

function onCellChange(row: GridRow) {
  // 已标删的行不参与脏检查:它的改动也不会进 updates
  if (row._status === 'deleted') return
  // 逐列与 _orig 比较,而不是记「改过」标志:这样改完又改回原值也能被识别出来
  const changed = columns.value.some((c) => row.v[c] !== row._orig[c])
  if (changed && row._status !== 'new') {
    row._status = 'modified'
  } else if (!changed && row._status === 'modified') {
    row._status = 'original' // 改回原值则撤销修改标记
  }
}

/** 构建定位行的键:优先用加载时的键,缺失则从原始值重建(兜底) */
function rowKey(r: GridRow): Record<string, any> {
  if (Object.keys(r._key).length) return r._key
  // 兜底:列裁剪导致 _key 不完整时按键列名回 _orig 里找;空值剔除,否则会拼出永假的 NULL = '' 条件
  const key: Record<string, any> = {}
  keyColumns.value.forEach((c) => {
    const val = r._orig[c]
    if (val !== '' && val != null) key[c] = val
  })
  return key
}

function stageDelete() {
  // 只打标记不发请求:与新增/修改凑成一次提交,失败时整批一起保留在暂存态便于重试
  rows.value.forEach((r) => {
    if (r._selected && r._status !== 'deleted') {
      r._status = 'deleted'
      // 顺手取消勾选,否则行已标删却仍被统计进 hasSelection,工具栏按钮状态会自相矛盾
      r._selected = false
    }
  })
}

/** 行底色标识暂存状态:新增/修改/删除 */
function rowClassName({ row }: { row: GridRow }): string {
  if (row._status === 'new') return 'row-new'
  if (row._status === 'modified') return 'row-modified'
  if (row._status === 'deleted') return 'row-deleted'
  return ''
}

async function commit() {
  // 收集阶段:按 _status 拆成三组,后面按「无有效载荷就不发」逐组过滤
  const inserts: Record<string, any>[] = []
  const updates: RowUpdate[] = []
  const deletes: Record<string, any>[] = []
  for (const r of rows.value) {
    if (r._status === 'new') {
      const row: Record<string, any> = {}
      // 空串不下发:有 DEFAULT/Nullable 的列应交给后端,发 '' 会被当成显式写入了空字符串
      columns.value.forEach((c) => {
        if (r.v[c] !== '' && r.v[c] != null) row[c] = r.v[c]
      })
      // 整行一个值都没填就跳过,避免写出一条全靠默认值的空记录
      if (Object.keys(row).length) inserts.push(row)
    } else if (r._status === 'modified') {
      const values: Record<string, any> = {}
      // 只发真正变化的列:既减少写入量,也避免把键列等无关字段带进 UPDATE
      columns.value.forEach((c) => {
        if (r.v[c] !== r._orig[c]) values[c] = r.v[c]
      })
      const keys = rowKey(r)
      // 没有变化列或定位不到行都跳过,否则后端会收到一条无法执行的 update
      if (Object.keys(values).length && Object.keys(keys).length) {
        updates.push({ keys, values })
      }
    } else if (r._status === 'deleted') {
      const keys = rowKey(r)
      // 同样要求键完整,缺键的删除直接丢弃
      if (Object.keys(keys).length) deletes.push(keys)
    }
  }
  // 三组都空说明前面的过滤把改动全滤掉了,此时不该发一个空请求
  if (!inserts.length && !updates.length && !deletes.length) return

  const summary = `新增 ${inserts.length} 行 · 修改 ${updates.length} 行 · 删除 ${deletes.length} 行`
  const ok = deletes.length
    ? await confirmDestructive('提交数据修改', props.table, '提交', `本次提交将删除 ${deletes.length} 行,删除不可恢复。变更明细:${summary}。`)
    : await confirmAction('提交数据修改', `确认提交对表「${props.table}」的修改？变更明细:${summary}。`, '确认提交')
  if (!ok) return

  saving.value = true
  try {
    const res = await workspaceApi.editData(props.connId, props.db, props.table, {
      keyColumns: keyColumns.value,
      inserts,
      updates,
      deletes,
    })
    ElMessage.success(`已提交:新增 ${res.inserted} · 修改 ${res.updated} · 删除 ${res.deleted}`)
    // 重新拉取整页:让自增主键、默认值、后端类型转换后的真实值回到界面,而不是留着本地草稿
    await reload()
  } catch {
    /* 拦截器已提示 */
    // 失败时保留暂存状态和所有改动,用户改完数据还能直接重试
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  autoQueryReady = false
  restoreQuery() // 先恢复,再按真实列清理
  await loadComments() // 字段备注 + 全字段列表(清理/查询前需要真实列)
  // 表结构可能已变化:过滤掉持久化条件中已不存在的列,避免每次加载都报「未知字段」
  rootFilter.value = sanitizeFilterColumns(rootFilter.value, new Set(allColumns.value)) ?? { logic: 'and', children: [] }
  appliedFilter.value = cleanFilter(rootFilter.value) ?? { logic: 'and', children: [] }
  // 先加载键列,再加载数据,确保行定位键(主键/排序键)已就绪
  await loadKeys()
  await reload()
  // 初始化请求全部发完才打开自动查询开关,否则恢复配置的过程会再触发一次重复查询
  autoQueryReady = true
})
</script>

<style scoped>
.data-editor {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}
.query-bar {
  flex: none;
  margin-bottom: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
  background: var(--fill-soft);
}
.query-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--fill-soft);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.query-title {
  flex: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  letter-spacing: 0.05em;
}
.qc-toggle {
  cursor: pointer;
  user-select: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 6px;
  border-radius: 4px;
}
.qc-toggle:hover {
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
}
.qc-arrow {
  display: inline-block;
  font-size: 11px;
  transition: transform 0.15s;
}
.qc-arrow.open {
  transform: rotate(90deg);
}
.qc-label {
  flex: none;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.f-show-cols {
  width: 220px;
}
.query-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.applied-tag {
  --el-tag-bg-color: transparent;
  margin-right: 4px;
}
.query-body {
  padding: 10px 12px;
}
.table-wrap {
  flex: 1;
  min-height: 0;
}
.edit-table {
  height: 100%;
}
.pager-bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 10px;
  padding: 0 0 14px; /* 底部留一点空间 */
}
.pager-bar :deep(.el-pagination) {
  --el-pagination-button-bg-color: var(--fill-soft);
}
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.dirty-hint {
  font-size: 13px;
  color: var(--brand-strong);
  margin-left: 4px;
}
.no-key-hint {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  margin-left: 4px;
}
.export-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-left: 8px;
}
.export-col-names {
  width: 100%;
  max-height: 160px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.export-col-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.export-col-orig {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.export-col-name-row .el-input {
  flex: 1.2;
}
.export-filename {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--brand-strong);
}
.export-progress {
  margin: 4px 0 8px;
  padding: 8px 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
}
.export-progress-text {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.edit-table {
  width: 100%;
}
.idx-col {
  color: var(--el-text-color-placeholder);
  font-family: var(--font-mono);
  font-size: 13px;
}
.col-head {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.3;
  padding: 2px 0;
  min-width: 0;
}
.col-head-line {
  display: flex;
  align-items: center;
  gap: 3px;
  min-width: 0;
  max-width: 100%;
}
.sort-arrows {
  display: inline-flex;
  flex: none;
  flex-direction: column;
  margin-left: 2px;
}
.sort-arrows button {
  appearance: none;
  border: 0;
  background: transparent;
  width: 13px;
  height: 9px;
  padding: 0;
  line-height: 9px;
  font-size: 8px;
  color: var(--el-text-color-placeholder);
  opacity: 0.55;
  cursor: pointer;
}
.sort-arrows button:hover,
.sort-arrows button.active {
  color: var(--el-color-primary);
  opacity: 0.9;
}
.col-head-comment {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
  margin-bottom: 1px;
}
/* 一条规则(以前写了两遍:前者定位/布局,后者字号/省略号,color 后者覆盖前者) */
.col-head-name {
  cursor: pointer;
  user-select: none;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-family: var(--font-ui);
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
.col-head-name.sort-active {
  color: var(--brand-strong);
}
.sort-no {
  font-size: 11px;
  background: var(--brand);
  color: #1a1a1a;
  border-radius: 3px;
  padding: 0 3px;
  font-weight: 600;
}
.cell-text {
  font-family: var(--font-mono);
  font-size: 13.5px;
  color: var(--el-text-color-regular);
  display: block;
  width: 100%;
  padding: 3px 4px;
  border-radius: 4px;
  cursor: text;
  line-height: 1.5;
  /* 长内容单行省略,悬停 title 看完整值 */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-text.cell-editable:hover {
  background: rgba(234, 179, 8, 0.07);
  box-shadow: inset 0 0 0 1px rgba(234, 179, 8, 0.25);
}
.cell-del {
  text-decoration: line-through;
  color: var(--el-text-color-placeholder);
}
.edit-table :deep(.row-new) {
  background: rgba(52, 211, 153, 0.07);
}
.edit-table :deep(.row-modified) {
  background: rgba(234, 179, 8, 0.07);
}
.edit-table :deep(.row-deleted) {
  background: rgba(248, 113, 113, 0.08);
}
.edit-table :deep(.row-deleted .cell) {
  text-decoration: line-through;
  color: var(--el-text-color-placeholder);
}
</style>
