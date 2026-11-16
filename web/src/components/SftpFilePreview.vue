<!--
  SftpFilePreview.vue

  职责：SFTP 文件预览：展示文本/表格预览及加载状态。
  边界：按数据源 id 在弹窗打开时加载预览；文本或表格模式取决于文件结构，关闭后允许重新请求。
-->

<template>
  <el-dialog v-model="vis" :title="`SFTP 文件预览 · ${name}`" width="900px" top="5vh" :close-on-click-modal="false">
    <div v-loading="loading" class="sfp">
      <!-- 目录工具栏 -->
      <div class="sfp-toolbar">
        <el-button size="small" :icon="Back" :disabled="!canUp" @click="goUp">上级</el-button>
        <el-button size="small" :icon="Refresh" @click="loadRefresh">刷新</el-button>
        <OverflowText class="sfp-path" :text="curPath || '…(家目录)'" />
        <!-- 仅在选中文件名存在时才出现,避免空按钮占位 -->
        <el-button v-if="curSelName" size="small" type="primary" :loading="pvLoading" @click="doPreview(selEntry)">预览选中文件</el-button>
      </div>

      <!-- 目录/文件列表 -->
      <el-table v-loading="dirLoading" :data="entries" size="small" border height="240" highlight-current-row
        :current-row-key="curSel" row-key="path" class="sftp-list" @current-change="onSel" @row-dblclick="onOpen">
        <el-table-column width="38">
          <template #default="{ row }"><el-icon :size="16"><Folder v-if="row.isDir" /><Document v-else /></el-icon></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="220">
          <template #default="{ row }"><OverflowText :text="row.name" /></template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.isDir ? 'warning' : 'info'">{{ row.isDir ? '目录' : fileType(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100" align="right">
          <template #default="{ row }"><span v-if="!row.isDir" class="mono">{{ fmtSize(row.size) }}</span></template>
        </el-table-column>
        <el-table-column label="修改时间" width="150">
          <template #default="{ row }"><span class="mono muted">{{ row.mtime }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="96" align="center">
          <template #default="{ row }">
            <!-- loading 只跟随当前行,避免整列按钮一起转圈 -->
            <el-button v-if="!row.isDir" size="small" type="success" plain :loading="pvLoading && curSel === row.path" @click.stop="doPreview(row)">预览</el-button>
            <el-button v-else size="small" type="primary" text @click.stop="load(row.path)">进入</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="sfp-hint">双击目录进入;单击文件选中,点「预览」查看内容</div>
      <div class="sfp-toolbar">
        <span>CSV 编码</span>
        <el-select v-model="csvEncoding" style="width: 160px" @change="refreshCsvPreview">
          <el-option label="自动识别" value="auto" />
          <el-option v-for="encoding in ['UTF-8', 'GB18030', 'GBK', 'UTF-16LE', 'UTF-16BE']" :key="encoding" :label="encoding" :value="encoding" />
        </el-select>
        <el-checkbox v-model="csvHeader" @change="refreshCsvPreview">首行为表头</el-checkbox>
        <el-checkbox v-model="ignoreExtra" @change="refreshCsvPreview">忽略额外尾列</el-checkbox>
      </div>
      <div v-if="pvErr" class="pv-err" role="alert">{{ pvErr }}</div>

      <!-- 文件预览区 -->
      <template v-if="preview">
        <div class="preview-meta">
          <!-- 数量标签用后端返回的 total(全量行数),不是本地截取的条数 -->
          <el-tag size="small" effect="plain" class="preview-file"><OverflowText :text="`${curPath}/${curSelName}`" /></el-tag>
          <el-tag size="small" effect="plain">预览 {{ rowsCount }} 条</el-tag>
          <el-tag v-if="preview.total != null && preview.total >= 0" size="small" type="success" effect="plain">共 {{ preview.total }} 行</el-tag>
          <el-tag v-if="preview.csvEncoding" size="small" effect="plain">{{ preview.csvEncoding }}</el-tag>
          <el-tag size="small" effect="plain">{{ columns.length }} 列</el-tag>
        </div>
        <el-table :data="previewRowsArr" v-loading="pvLoading" border size="small" max-height="320" class="preview-table">
          <!-- 列名/类型由后端预览结果给出,表格本身不依赖文件头解析 -->
          <el-table-column type="index" label="序号" width="60" align="center" />
          <el-table-column v-for="(col, ci) in columns" :key="ci" width="180">
            <template #header>
              <div class="preview-col-head"><OverflowText class="preview-col-name" :text="col.name" /><OverflowText class="preview-col-type" :text="col.type" /></div>
            </template>
            <template #default="{ $index }"><OverflowText :text="cell($index, ci)" /></template>
          </el-table-column>
        </el-table>
      </template>
    </div>
    <template #footer>
      <el-button @click="vis = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Back, Document, Folder, Refresh } from '@element-plus/icons-vue'
import { dataSourcesApi } from '@/api'
import type { SftpEntry } from '@/types'
import OverflowText from '@/components/OverflowText.vue'

const props = defineProps<{ id: number; name?: string; csvEncoding?: string | null; header?: boolean; ignoreExtraCsvColumns?: boolean }>()
const csvEncoding = ref(props.csvEncoding ?? 'auto')
const csvHeader = ref(props.header !== false)
const ignoreExtra = ref(props.ignoreExtraCsvColumns !== false)
const vis = defineModel<boolean>({ required: true })

const loading = ref(false)
const dirLoading = ref(false)
const curPath = ref('') // 空=家目录
const entries = ref<SftpEntry[]>([])
const curSel = ref('')
const curSelName = ref('')
const selEntry = ref<SftpEntry | null>(null)

const preview = ref<{ columns: { name: string; type: string }[]; rows: { columns: string[]; rows: string[][] }; total?: number | null; csvEncoding?: string } | null>(null)
const pvLoading = ref(false)
const pvErr = ref('')

const columns = computed(() => preview.value?.columns ?? [])
const rowsCount = computed(() => preview.value?.rows.rows.length ?? 0)
// 表格只吃对象数组,这里按行数造索引壳,单元格值再通过 cell() 按坐标取
const previewRowsArr = computed(() => {
  const n = preview.value?.rows.rows.length ?? 0
  return Array.from({ length: n }, (_, i) => ({ __i: i }))
})
// 行列都可能缺位(某行短于表头),统一兜底空串以免渲染 undefined
function cell(i: number, ci: number) { return preview.value?.rows.rows[i]?.[ci] ?? '' }

const canUp = computed(() => curPath.value !== '' && curPath.value !== '/')

// 请求序号:目录/预览并发时丢弃过期响应,避免慢响应覆盖新目录/新预览导致界面“回跳”
let dirSeq = 0
let pvSeq = 0

async function load(p = curPath.value) {
  const seq = ++dirSeq
  pvSeq++ // 切换目录会清空预览,作废正在途中的文件预览
  dirLoading.value = true
  try {
    const res = await dataSourcesApi.sftpBrowse({ id: props.id, path: p })
    if (seq !== dirSeq) return // 已有更新的目录请求,丢弃本次
    curPath.value = res.path
    entries.value = res.entries
    // 新目录里旧选中项已不存在,全部清空,否则预览按钮会指向别的目录的文件
    curSel.value = ''
    curSelName.value = ''
    selEntry.value = null
    preview.value = null
  } catch (e: any) {
    if (seq !== dirSeq) return
    ElMessage.error(e?.message ?? '读取目录失败')
  } finally {
    if (seq === dirSeq) dirLoading.value = false
  }
}
function loadRefresh() { load(curPath.value) }
function goUp() {
  const p = curPath.value
  if (!p || p === '/') return
  // 退到上一级;去掉末段后为空则落回根目录
  const i = p.lastIndexOf('/')
  load(i <= 0 ? '/' : p.slice(0, i))
}
/** 单击/选中文件:仅高亮,不预览(预览由按钮触发) */
function onSel(row: SftpEntry | null) {
  // 目录行或取消选中:清掉选中态,让工具栏的预览按钮一并消失
  if (!row || row.isDir) { curSel.value = ''; curSelName.value = ''; selEntry.value = null; return }
  curSel.value = row.path
  curSelName.value = row.name
  selEntry.value = row
}
function onOpen(row: SftpEntry) {
  // 双击目录进入;双击文件不触发预览,避免与单击选中的语义混淆
  if (row.isDir) load(row.path)
}
function doPreview(row: SftpEntry | null) {
  // 工具栏按钮可能带着旧选中项调用,这里再做一次守卫
  if (!row || row.isDir) { ElMessage.info('请先单击选择要预览的文件'); return }
  // 行内按钮直接预览时同步选中态,保证高亮行与预览内容一致
  curSel.value = row.path
  curSelName.value = row.name
  selEntry.value = row
  previewFile(row)
}

async function previewFile(row: SftpEntry) {
  const seq = ++pvSeq
  pvLoading.value = true
  pvErr.value = ''
  try {
    // limit 固定 50:大文件只取头部若干行,避免整文件读取拖垮浏览器
    const r = await dataSourcesApi.sftpPreview({ id: props.id, path: row.path, limit: 50,
      csvEncoding: csvEncoding.value, header: csvHeader.value, ignoreExtraCsvColumns: ignoreExtra.value })
    if (seq !== pvSeq) return // 期间目录/预览已切换,丢弃过期响应
    preview.value = {
      columns: r.columns ?? [],
      // rows 可能是后端省略字段(null/undefined),逐层兜底成空数组
      rows: { columns: (r.rows as any)?.columns ?? [], rows: (r.rows as any)?.rows ?? [] },
      total: r.total,
      csvEncoding: r.csvEncoding,
    }
  } catch (e: any) {
    if (seq !== pvSeq) return
    // 失败时清掉旧预览并单独展示错误,不弹全局 toast(弹窗内联更易定位)
    preview.value = null
    pvErr.value = e?.message ?? '预览失败'
  } finally {
    if (seq === pvSeq) pvLoading.value = false
  }
}

function refreshCsvPreview() {
  if (selEntry.value && !selEntry.value.isDir) void previewFile(selEntry.value)
}
watch(() => [props.id, props.csvEncoding, props.header, props.ignoreExtraCsvColumns], () => {
  csvEncoding.value = props.csvEncoding ?? 'auto'
  csvHeader.value = props.header !== false
  ignoreExtra.value = props.ignoreExtraCsvColumns !== false
  pvSeq++
  pvLoading.value = false
  preview.value = null
  pvErr.value = ''
})

function fileType(row: SftpEntry): string {
  const n = row.name.toLowerCase()
  if (n.endsWith('.csv')) return 'CSV'
  if (n.endsWith('.json')) return 'JSON'
  // 无扩展名(含点号才切)归为通用「文件」,避免把整串文件名当扩展名显示
  return n.includes('.') ? n.split('.').pop()!.toUpperCase() : '文件'
}
// 1024 进制逐级降单位;字节数或 >=100 的整数量不显示小数,减少视觉噪声
function fmtSize(n: number): string {
  if (!n) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`
}

// immediate:弹窗初始就是打开态时也要立刻加载,否则要等下一次开关才出内容
watch(vis, (v) => { if (v) { preview.value = null; load('') } }, { immediate: true })
</script>

<style scoped>
.sfp-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.sfp-list { cursor: pointer; }
.sfp-list :deep(.el-table__row) { cursor: pointer; }
.sfp-list :deep(.el-table__row:hover > td) { background: var(--hover-bg-strong) !important; }
/* 选中(当前)行:浅暖米背景 + 深色文字,保证可读 */
.sfp-list :deep(.el-table__row.current-row > td) {
  background: #fbefd3 !important;
  color: #7a4a00;
}
.sfp-list :deep(.el-table__row.current-row .mono),
.sfp-list :deep(.el-table__row.current-row .muted),
.sfp-list :deep(.el-table__row.current-row .cell),
.sfp-list :deep(.el-table__row.current-row .el-icon) {
  color: #7a4a00;
}
.sfp-path { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.sfp-hint { font-size: 11.5px; color: var(--el-text-color-secondary); margin: 6px 0; }
.preview-meta { display: flex; align-items: center; gap: 8px; margin: 4px 0; flex-wrap: wrap; }
.preview-file { max-width: 60%; }
.preview-file :deep(.el-tag__content) { min-width: 0; overflow: hidden; }
.preview-col-head { display: flex; flex-direction: column; }
.preview-col-name { font-weight: 600; font-size: 12px; }
.preview-col-type { font-size: 11px; color: var(--el-text-color-secondary); }
.pv-err { color: var(--el-color-danger); font-size: 12px; margin-top: 4px; }
.muted { color: var(--el-text-color-secondary); font-size: 11px; }
</style>
