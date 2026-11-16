<!--
  SftpBrowserDialog.vue

  职责：SFTP 文件浏览弹窗：加载目录、选择文件并返回路径。
  边界：由 modelValue 控制并在打开时加载 SFTP 目录；逐级浏览后把选中路径交回父级，加载失败时保留当前目录上下文。
-->

<template>
  <el-dialog v-model="vis" :title="title" width="640px" :close-on-click-modal="false" destroy-on-close>
    <div class="sftpb">
      <div class="sftpb-toolbar">
        <el-button size="small" :icon="Back" :disabled="!canUp" @click="goUp">上级</el-button>
        <OverflowText class="sftpb-path" :text="curPath || '…'" />
        <!-- okDisabled 实为"已选中可确认的文件",取反后作为禁用条件 -->
        <el-button v-if="mode === 'file'" size="small" type="primary" :disabled="!okDisabled" @click="emitOK">
          确认{{ mode === 'file' ? '此文件' : '此目录' }}
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" size="small" border height="300" highlight-current-row
        class="sftp-browse-tbl"
        @current-change="onCurrent" @row-dblclick="onRowDbl" current-row-key="path">
        <el-table-column width="40">
          <template #default="{ row }"><el-icon :size="16"><Folder v-if="row.isDir" /><Document v-else /></el-icon></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="180"><template #default="{ row }"><OverflowText :text="row.name" /></template></el-table-column>
        <el-table-column v-if="mode === 'file'" label="大小" width="90">
          <template #default="{ row }"><span v-if="!row.isDir" class="mono">{{ fmtSize(row.size) }}</span></template>
        </el-table-column>
      </el-table>
      <!-- 后端返回空目录时不报错,单独给出空态提示 -->
      <div class="sftpb-empty" v-if="!loading && !rows.length">目录为空</div>

      <!-- dir 模式:文件匹配 + 自动检测结果 -->
      <template v-if="mode === 'dir' && detect">
        <div class="sftpb-detect">
          <div class="det-row">
            <span class="det-label">文件匹配</span>
            <el-input v-model="pattern" size="small" placeholder="*.csv / cust_*.csv" style="width: 220px" class="mono" @keyup.enter="pickCurDir" />
            <el-button size="small" type="primary" :loading="detecting" @click="pickCurDir">选定此目录并检测</el-button>
          </div>
          <div v-if="detResult" class="det-sum">
            <!-- 目录/匹配数/一致数/不一致数四类汇总,方便判断这批文件能否直接同步 -->
            <el-tag size="small" type="warning" effect="plain" class="directory-tag"><OverflowText :text="pickedDir" /></el-tag>
            <el-tag size="small" effect="plain">匹配 {{ detResult.files.length }}</el-tag>
            <el-tag size="small" type="success" effect="plain">一致 {{ okCount }}</el-tag>
            <el-tag size="small" :type="badCount ? 'danger' : 'info'" effect="plain">不一致 {{ badCount }}</el-tag>
          </div>
          <div v-if="detecting" class="det-hint">正在连接并检测目录文件…</div>
          <!-- 只压缩显示，悬停保留全部参考列，便于核对后面的差异。 -->
          <el-table v-if="detResult && detResult.files.length" :data="detResult.files" size="small" border max-height="220" class="det-tbl">
            <el-table-column prop="path" label="远端文件" min-width="230"><template #default="{ row }"><OverflowText :text="row.path" /></template></el-table-column>
            <el-table-column label="列" width="52" align="center"><template #default="{ row }">{{ row.columnCount }}</template></el-table-column>
            <el-table-column label="结构" width="96" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.error" size="small" type="danger" effect="plain" :title="row.error">失败</el-tag>
                <el-tag v-else-if="row.sameSchema" size="small" type="success" effect="plain">一致</el-tag>
                <el-tag v-else size="small" type="danger" effect="plain">不一致</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="参考列" min-width="150">
              <template #default="{ row }">
                <OverflowText v-if="!row.error" :text="(row.columns || []).join(', ')" :detail="(row.columns || []).join('\n')" />
                <OverflowText v-else :text="row.error" />
              </template>
            </el-table-column>
          </el-table>
          <!-- 检测成功但零匹配:明确提示当前模式串,避免误以为目录读失败 -->
          <div v-if="detResult && !detResult.files.length" class="det-hint">该目录下没有匹配 {{ pattern }} 的文件</div>
          <!-- 不一致文件同步阶段会被排除,提前告知以免用户以为数据漏同步 -->
          <div v-if="badCount" class="det-warn">不一致的文件在同步时会被自动排除</div>
        </div>
      </template>
    </div>

    <template #footer>
      <el-button @click="vis = false">取消</el-button>
      <!-- detect 模式必须先在弹窗内完成检测(pickedDir 非空)才允许确认 -->
      <template v-if="mode === 'dir' && detect">
        <el-button type="primary" :disabled="!pickedDir" @click="confirmPick">确认使用此目录</el-button>
      </template>
      <!-- 普通目录模式无候选概念,确认即当前所在目录 -->
      <template v-else-if="mode === 'dir'">
        <el-button type="primary" @click="confirmDir">确认此目录</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Back, Document, Folder } from '@element-plus/icons-vue'
import { dataSourcesApi } from '@/api'
import type { SftpDirPreview, SftpEntry } from '@/types'
import OverflowText from '@/components/OverflowText.vue'

const props = defineProps<{
  /** 连接凭据(未保存时直连;或已保存源 id) */
  conn: { id?: number; host?: string; port?: number; username?: string; password?: string }
  mode?: 'dir' | 'file' // dir=选目录(远程路径);file=选 CSV 文件(预览列用)
  title?: string
  /** dir 模式:开启则需选目录并自动检测匹配文件(detResult 展示),确认才回填 */
  detect?: boolean
  filePattern?: string
}>()
const emit = defineEmits<{
  (e: 'ok', path: string, filePattern?: string): void
}>()

const vis = defineModel<boolean>({ required: true })
const loading = ref(false)
const curPath = ref('') // 当前浏览目录(空=家目录)
const rows = ref<SftpEntry[]>([])

const mode = computed(() => props.mode ?? 'dir')
const detect = computed(() => props.detect === true && mode.value === 'dir')
const title = computed(() => props.title ?? (mode.value === 'file' ? '选择远端文件' : '选择远端目录'))

const pattern = ref(props.filePattern || '*.csv')
const pickedDir = ref('') // 已选定待检测的目录
const detResult = ref<SftpDirPreview | null>(null)
const detecting = ref(false)

// 未保存的连接没有 id,只能带凭据直连;两者都传给后端,由后端决定用哪个
function cred() {
  return { id: props.conn.id, host: props.conn.host, port: props.conn.port, username: props.conn.username, password: props.conn.password }
}
// 空路径代表家目录,已经是根目录时无上级可回
const canUp = computed(() => curPath.value !== '' && curPath.value !== '/')
let browseSeq = 0 // 目录浏览并发:丢弃过期响应,避免慢响应回跳旧目录

async function load(p = curPath.value) {
  const seq = ++browseSeq
  loading.value = true
  try {
    const res = await dataSourcesApi.sftpBrowse({ ...cred(), path: p })
    // 期间又发起了新的浏览请求,本次结果已过期,直接丢弃(连 loading 都不再动)
    if (seq !== browseSeq) return
    curPath.value = res.path
    rows.value = res.entries
    // 切目录后原选中项属于旧目录,必须清空,否则确认按钮状态残留
    dirSel.value = null
    pickedFile.value = null
  } catch (e: any) {
    if (seq !== browseSeq) return
    ElMessage.error(e?.message ?? '浏览目录失败')
  } finally {
    if (seq === browseSeq) loading.value = false
  }
}

/** 选中文件引用(仅 file 模式) */
const pickedFile = ref<SftpEntry | null>(null)
/** dir 模式:单击选中的子目录候选(进入后确认用当前所在目录) */
const dirSel = ref<SftpEntry | null>(null)
/** file 模式确认(工具栏按钮) */
const okDisabled = computed(() => !!pickedFile.value && !pickedFile.value.isDir)

function onCurrent(row: SftpEntry | null) {
  if (detect.value) return // dir+detect:单击不选,双击进入子目录再检测
  if (mode.value === 'file') {
    pickedFile.value = row
  } else {
    // dir:单击目录选中为候选,再点确认;文件不可选
    dirSel.value = row && row.isDir ? row : null
  }
}
function onRowDbl(row: SftpEntry) {
  // 双击只对目录生效,文件双击不直接确认,避免误选
  if (row.isDir) load(row.path)
}
function goUp() {
  const p = curPath.value
  if (!p || p === '/') return
  // 去掉最后一段;上一级为空(如 /a)时回到根而不是空串
  const i = p.lastIndexOf('/')
  const parent = i <= 0 ? '/' : p.slice(0, i)
  load(parent)
}

// 1024 进制逐级降单位;整数或 >=100 的值不保留小数,避免 100.0 KB 这种冗余精度
function fmtSize(n: number): string {
  if (!n) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`
}

/** 选定当前所在目录并自动检测(在弹窗内展示结果) */
async function pickCurDir() {
  const dir = curPath.value || ''
  // 家目录(空路径)不能作为同步目录,先要求用户点进具体目录
  if (!dir) { ElMessage.warning('请先进入一个目录'); return }
  pickedDir.value = dir
  // 换目录即作废旧检测结果,防止旧结果和按钮文案对不上
  detResult.value = null
  detecting.value = true
  try {
    // 匹配串留空时回落 *.csv;后端可能规范化模式,用返回值回写输入框与用户所见一致
    const p = pattern.value.trim() || '*.csv'
    detResult.value = await dataSourcesApi.sftpDirPreview({
      // 凭据字段全部转成字符串/数字兜底,避免 undefined 序列化后传到后端报错
      host: String(props.conn.host ?? ''), port: Number(props.conn.port ?? 22),
      username: String(props.conn.username ?? ''), password: String(props.conn.password ?? ''),
      remotePath: dir, filePattern: p,
    })
    pattern.value = detResult.value.pattern || p
  } catch (e: any) {
    ElMessage.error(e?.message ?? '目录文件检测失败')
  } finally {
    detecting.value = false
  }
}

// 一致/不一致均以"检测失败"优先计入 bad,失败文件同样不能参与同步
const okCount = computed(() => detResult.value?.files.filter(f => !f.error && f.sameSchema).length ?? 0)
const badCount = computed(() => detResult.value?.files.filter(f => f.error || !f.sameSchema).length ?? 0)

/** dir+detect:确认使用已检测的目录 */
function confirmPick() {
  if (!pickedDir.value) { ElMessage.warning('请先选定并检测一个目录'); return }
  const p = pattern.value.trim() || '*.csv'
  // 先关闭弹窗再回传,避免父组件重渲染期间弹窗仍可见造成闪烁
  vis.value = false
  emit('ok', pickedDir.value, p)
}

/** dir(非检测):确认所选目录 — 优先用单击选中的子目录,否则当前所在目录 */
function confirmDir() {
  const dir = dirSel.value?.path ?? curPath.value
  if (!dir) { ElMessage.warning('请单击选择或进入一个目录'); return }
  vis.value = false
  emit('ok', dir)
}

/** file 模式确认 */
function emitOK() {
  // 目录行不可作为文件选择,双击目录是"进入"而非选中
  if (!pickedFile.value || pickedFile.value.isDir) { ElMessage.warning('请选择一个文件'); return }
  const path = pickedFile.value.path
  vis.value = false
  emit('ok', path)
}

// 每次打开都从家目录重新开始并清空检测态:上一次的目录/结果不跨次复用
watch(vis, (v) => {
  if (!v) return
  pickedDir.value = ''
  detResult.value = null
  detecting.value = false
  load('')
})
defineExpose({ open: () => { vis.value = true; load('') } })
</script>

<style scoped>
.directory-tag { max-width: 100%; }
.directory-tag :deep(.el-tag__content) { min-width: 0; overflow: hidden; }
.sftpb-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.sftp-browse-tbl { cursor: pointer; }
.sftp-browse-tbl :deep(.el-table__row) { cursor: pointer; }
.sftp-browse-tbl :deep(.el-table__row:hover > td) { background: var(--hover-bg-strong) !important; }
/* 选中(当前)行:浅暖米背景 + 深色文字,保证可读 */
.sftp-browse-tbl :deep(.el-table__row.current-row > td) {
  background: #fbefd3 !important;
  color: #7a4a00;
}
.sftp-browse-tbl :deep(.el-table__row.current-row .mono),
.sftp-browse-tbl :deep(.el-table__row.current-row .muted),
.sftp-browse-tbl :deep(.el-table__row.current-row .cell),
.sftp-browse-tbl :deep(.el-table__row.current-row .el-icon) {
  color: #7a4a00;
}
.sftpb-path { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.sftpb-empty { text-align: center; color: var(--el-text-color-secondary); padding: 24px; font-size: 13px; }
.sftpb-detect { margin-top: 10px; border-top: 1px solid var(--el-border-color-lighter); padding-top: 8px; }
.det-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.det-label { font-size: 12px; color: var(--el-text-color-secondary); flex-shrink: 0; }
.det-sum { display: flex; align-items: center; gap: 6px; margin: 4px 0; flex-wrap: wrap; }
.det-tbl { width: 100%; }
.det-hint { font-size: 12px; color: var(--el-text-color-secondary); padding: 8px 0; }
.det-warn { font-size: 12px; color: var(--el-color-danger); margin-top: 4px; }
.det-col { font-size: 11px; color: var(--el-text-color-secondary); }
.muted { color: var(--el-text-color-secondary); }
.small { font-size: 11px; }
</style>
