<!--
  HashToolView.vue

  职责：哈希工具页面：计算并展示文本摘要结果。
  边界：解析多行输入并分批调用哈希接口，支持结果分页和导出；算法和大小写偏好持久化，失败时不保留半份结果。
-->

<template>
  <div class="hash-tool-view">
    <!-- 页头:与其它页面同一套(page-head / page-sub) -->
    <div class="page-head">
      <div>
        <h2>哈希计算</h2>
        <p class="page-sub">
          把原始数据算成哈希(MD5 / SHA256 / SM3),每行一个。
          每行首尾空白会被去掉(不然从 Excel / CRLF 文件里粘进来的数据会因不可见字符算错);
          默认输出大写 —— 与平台一致(平台算子的 hex() 与 SM3 都返回大写)。
        </p>
      </div>
    </div>

    <!-- 输入区 -->
    <el-card shadow="never" class="ht-card">
      <div class="ht-form">
        <!--
          算法用按钮组而不是下拉:只有 3 个值,点一下即选(少一次展开);
          而且这一页的 el-select 会触发 EP 的递归更新告警
          ("Maximum recursive updates exceeded in <ElSelect>"),
          它会把同一卡片里表格的列注册打断(行在、单元格全没)。
        -->
        <el-radio-group v-model="algo" class="ht-algo" aria-label="哈希算法">
          <el-radio-button v-for="a in algos" :key="a" :value="a">{{ a.toUpperCase() }}</el-radio-button>
        </el-radio-group>
        <label class="ht-upper">
          <el-switch v-model="upper" aria-label="大写输出" />
          <span>大写输出</span>
        </label>
      </div>

      <el-input
        v-model="raw"
        type="textarea"
        :rows="6"
        resize="vertical"
        placeholder="每行一个待计算的值,例如：helloworld"
        aria-label="待计算的数据"
      />

      <div class="ht-run">
        <el-button type="primary" :loading="loading" @click="run">立即计算</el-button>
        <el-button @click="pickFile">上传文件</el-button>
        <el-button text :disabled="!raw" @click="clearAll">清空</el-button>
        <span class="ht-count">{{ values.length }} 行</span>
        <input ref="fileRef" type="file" accept=".txt,.csv,text/plain" class="ht-file" @change="onFile" />
      </div>

      <!--
        进度条:只在需要分批(>CHUNK 行)时出现。
        分批不只是为了进度 —— 以前是把全部行塞进一个请求,几十万行时
        请求体、响应体、前端内存三处同时到峰值,且中途没有任何反馈。
      -->
      <div v-if="progressTotal > CHUNK" class="ht-progress">
        <el-progress
          :percentage="Math.round((progressDone / progressTotal) * 100)"
          :stroke-width="10"
          :status="progressDone >= progressTotal ? 'success' : undefined"
        />
        <span class="ht-progress-text">{{ progressDone }} / {{ progressTotal }} 条</span>
      </div>
    </el-card>

    <!-- 结果区:占满剩余高度,表格内部滚动 -->
    <el-card v-if="result" shadow="never" class="ht-card ht-result-card">
      <div class="ht-stats">
        <span>{{ result.algo.toUpperCase() }}</span>
        <span>{{ result.upper ? '大写' : '小写' }}</span>
        <span>共 <b>{{ result.count }}</b> 条</span>

        <span class="ht-export">
          <el-button size="small" @click="openExport">导出</el-button>
        </span>
      </div>

      <div class="ht-table-wrap">
        <el-table :data="pagedItems" border stripe height="100%">
          <el-table-column type="index" label="序号" width="60" align="center" />
          <el-table-column prop="value" label="原值" show-overflow-tooltip min-width="200" />
          <el-table-column label="哈希值" min-width="320">
            <template #default="{ row }">
              <span class="ht-hash">{{ row.hash }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center">
            <template #default="{ row }">
              <el-button link size="small" @click="copyHash(row.hash)">复制</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!--
        分页:固定在卡片底部,不随表格滚动。
        故意不放「每页条数」下拉 —— 它在这一页会与表格渲染互相触发(Vue 报
        "Maximum recursive updates exceeded in <ElSelect>",整个卡片渲染被中断,
        表格变成只有行没有单元格);而这页也不需要选每页条数,固定 100 行够了。
      -->
      <div class="ht-pager">
        <el-pagination
          background
          layout="total, prev, pager, next, jumper"
          :total="result.items.length"
          :current-page="page"
          :page-size="pageSize"
          @current-change="onPageChange"
        />
      </div>
    </el-card>

    <el-card v-else shadow="never" class="ht-card ht-idle">
      还没有计算结果:贴入数据后点「立即计算」。
    </el-card>

    <!--
      导出选项:内容(是否含原值) / 是否带表头 / 自定义表头名。
      写法与平台其它导出弹窗一致(el-dialog + el-form label-position="left")。
    -->
    <el-dialog v-model="exportVisible" title="导出哈希结果" width="480px" :close-on-click-modal="false">
      <el-form label-width="88px" label-position="left">
        <el-form-item label="导出内容">
          <el-radio-group v-model="exportOpts.withValues">
            <el-radio :value="true">原值 + 哈希</el-radio>
            <el-radio :value="false">仅哈希值</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="表头">
          <el-switch v-model="exportOpts.withHeader" />
          <span class="ht-hint">第一行输出列名</span>
        </el-form-item>

        <!-- 列名只在有表头时可填;「仅哈希值」时只有一列 -->
        <el-form-item v-if="exportOpts.withHeader && exportOpts.withValues" label="列名">
          <div class="ht-names">
            <el-input v-model="exportOpts.valueName" size="small" placeholder="原值列名" />
            <el-input v-model="exportOpts.hashName" size="small" placeholder="哈希列名" />
          </div>
        </el-form-item>
        <el-form-item v-else-if="exportOpts.withHeader" label="列名">
          <el-input v-model="exportOpts.hashName" size="small" placeholder="哈希列名" />
        </el-form-item>

        <el-form-item label="文件名">
          <span class="ht-filename">{{ exportFilename }}</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button type="primary" @click="doExport">导出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 工具 · 哈希计算(明文 → MD5 / SHA256 / SM3)。
 *
 * 为什么在后端算:浏览器只有 crypto.subtle(仅 SHA-256),MD5 与 SM3 都要另引 JS 库;
 * 后端本来就有国密 SM3 依赖。
 *
 * 两个刻意做的取舍:
 *  1. 按【换行】切分,并按行 trim —— 哈希对空白敏感,粘贴常带 \r 与尾随空格,
 *     不 trim 会静默算出一堆"看着对其实错"的哈希。逗号不切分(值本身可能含逗号)。
 *  2. 默认大写 —— 平台里 ClickHouse hex() 与 SM3 算子都是大写。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { hashToolApi } from '@/api'
import type { HashToolItem, HashToolResult } from '@/types'
import { CSV_BOM, csvRow, downloadBlob } from '@/utils/download'

/** 设置项(算法/大小写)的本地记忆键 */
const LS_KEY = 'el2ck.hashTool'

const algo = ref('md5')
const algos = ref<string[]>(['md5', 'sha256', 'sm3'])
const upper = ref(true)
const raw = ref('')

const loading = ref(false)
const result = ref<HashToolResult | null>(null)

/**
 * 每次请求的条数上限。
 *
 * 分批的三个理由:① 请求体/响应体不集中成一个几十 MB 的大包;
 * ② 批与批之间能刷帧,进度条才动得起来;③ 单批远小于 http.ts 的 120s 超时。
 */
const CHUNK = 5000
/** 已算条数 / 总条数(progressTotal > CHUNK 时才显示进度条) */
const progressDone = ref(0)
const progressTotal = ref(0)
const fileRef = ref<HTMLInputElement>()

/** 导出选项弹窗 */
const exportVisible = ref(false)
/**
 * 导出选项。
 *
 * withValues=true → CSV(原值 + 哈希);false → TXT(仅哈希,一行一个)。
 * 表头名可改:平台里其它导出弹窗也允许逐列改名,导出给别的系统用时需要按它的列名来。
 */
const exportOpts = ref({
  withValues: true,
  withHeader: true,
  valueName: '原值',
  hashName: '哈希值'
})

/**
 * 解析:按换行切分 + 去掉每行首尾空白。
 *
 * ⚠️ trim 不是可选的美化:CRLF 文件 / Excel 复制过来的每行都带 \r,
 *    不去掉的话算出来的是 "13800138001\r" 的哈希 —— 长度对、看着也对,就是查不到。
 */
const values = computed(() =>
  raw.value
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
)

/**
 * 分页。
 *
 * 以前是「只渲染前 500 行 + 一句提示」:算几万行时后面根本翻不到。
 * 改成全量分页(每页最多 500) —— 表格没虚拟滚动,一页 500 行已是上限,
 * 但能翻到任意一条;导出仍是全量。
 */
const page = ref(1)
/** 每页固定 100 行 —— 表格没虚拟滚动,这是渲染上限内的合适值 */
const pageSize = ref(100)
const pagedItems = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return (result.value?.items ?? []).slice(start, start + pageSize.value)
})

function onPageChange(p: number) {
  page.value = p
}

// 新一次计算 → 回到第 1 页(沿用旧页码会因结果变少而显示空白)
watch(result, () => {
  page.value = 1
})

/** 导出文件名(随格式变化,弹窗里直接显示给用户看) */
const exportFilename = computed(
  () => `hash-${(result.value?.algo ?? algo.value).toLowerCase()}.${exportOpts.value.withValues ? 'csv' : 'txt'}`
)

/**
 * 计算。
 *
 * ⚠️ 失败不在 catch 里弹提示:http.ts 拦截器已统一弹过,这里再弹会重复。
 */
async function run() {
  const vals = values.value
  if (!vals.length) {
    ElMessage.warning('请先输入要计算的数据(每行一个)')
    return
  }
  loading.value = true
  progressTotal.value = vals.length
  progressDone.value = 0
  const items: HashToolItem[] = []
  try {
    for (let i = 0; i < vals.length; i += CHUNK) {
      const part = await hashToolApi.digest({
        algo: algo.value,
        values: vals.slice(i, i + CHUNK),
        upper: upper.value
      })
      for (const it of part.items) items.push(it)
      progressDone.value = Math.min(i + CHUNK, vals.length)
      // 让出一次事件循环:不这样做的话进度条要到全部算完才画得出来
      // (用 setTimeout 而不是 rAF —— 测试环境里 rAF 是被打桩的)
      if (i + CHUNK < vals.length) await new Promise((r) => setTimeout(r, 0))
    }
    result.value = { items, algo: algo.value, upper: upper.value, count: items.length }
  } catch {
    // 中途失败:不留半份结果(页码由 watch(result) 归位)
    result.value = null
  } finally {
    loading.value = false
    progressTotal.value = 0
    progressDone.value = 0
  }
}

async function copyHash(hash: string) {
  try {
    await navigator.clipboard.writeText(hash)
    ElMessage.success('已复制')
  } catch {
    // 非 HTTPS / 无权限时 clipboard 不可用 —— 明确告诉用户,别让他以为复制成功了
    ElMessage.error('浏览器不允许自动复制,请手动选择文本')
  }
}

function pickFile() {
  fileRef.value?.click()
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    raw.value = await file.text()
  } catch {
    ElMessage.error('读取文件失败')
  } finally {
    // 清掉 value,否则同一个文件再选一次不会触发 change
    input.value = ''
  }
}

function clearAll() {
  raw.value = ''
  result.value = null
}

/**
 * 导出:按选项拼内容。
 *
 * ⚠️ CSV 才加 BOM(Excel 打开中文/特殊字符会乱码);TXT 不加 ——
 *    它是拿去喂别的程序的一行一个,多加三个字节反而可能被判成非法字符。
 * ⚠️ 表头名留空 = 不写表头行:用户把列名清空通常就是"这行不要",
 *    而不是想要一个空表头("\"\",\"\""这种空表头在导入时很容易报错)。
 */
function buildExport(): { text: string; filename: string; mime: string } {
  const items = result.value?.items ?? []
  const o = exportOpts.value
  const valueName = o.valueName.trim()
  const hashName = o.hashName.trim()
  const lines: string[] = []

  if (o.withValues) {
    // 值里可能含逗号/引号/换行 —— 交给 csvRow 转义
    if (o.withHeader && (valueName || hashName)) lines.push(csvRow([valueName, hashName]))
    for (const it of items) lines.push(csvRow([it.value, it.hash]))
    return {
      text: CSV_BOM + lines.join('\n') + '\n',
      filename: exportFilename.value,
      mime: 'text/csv;charset=utf-8'
    }
  }

  if (o.withHeader && hashName) lines.push(hashName)
  for (const it of items) lines.push(it.hash)
  return {
    text: lines.join('\n') + '\n',
    filename: exportFilename.value,
    mime: 'text/plain;charset=utf-8'
  }
}

function openExport() {
  if (!result.value?.items.length) {
    ElMessage.warning('还没有结果可导出')
    return
  }
  exportVisible.value = true
}

function doExport() {
  const items = result.value?.items ?? []
  if (!items.length) return
  const f = buildExport()
  downloadBlob(new Blob([f.text], { type: f.mime }), f.filename)
  exportVisible.value = false
  ElMessage.success(`已导出 ${items.length} 条`)
}

/** 算法与大小写的本地记忆(下次打开保持上次的口径) */
function saveSettings() {
  localStorage.setItem(LS_KEY, JSON.stringify({ algo: algo.value, upper: upper.value }))
}

watch([algo, upper], saveSettings)

onMounted(async () => {
  await hashToolApi
    .config()
    .then((c) => {
      if (c.algos?.length) algos.value = c.algos
    })
    .catch(() => {
      /* 取不到就用内置清单,不影响使用 */
    })

  const saved = localStorage.getItem(LS_KEY)
  if (saved) {
    try {
      const s = JSON.parse(saved) as { algo?: string; upper?: boolean }
      if (s.algo && algos.value.includes(s.algo)) algo.value = s.algo
      if (typeof s.upper === 'boolean') upper.value = s.upper
    } catch {
      /* 本地记忆坏了就当没有 */
    }
  }
})
</script>

<style scoped>
.hash-tool-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px 24px;
  box-sizing: border-box;
  overflow: hidden;
}

/* ── 页头:与其它页面同一份 ── */
.page-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-head h2 {
  margin: 0 0 4px;
  font-size: 18px;
}
.page-sub {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* ── 输入卡 ── */
.ht-card {
  flex: none;
}
.ht-card :deep(.el-card__body) {
  padding: 14px 16px;
}
.ht-form {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.ht-upper {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  user-select: none;
}
.ht-run {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.ht-count {
  margin-left: auto;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
/* 分批进度:另起一行,不挤进上面的 flex 行 */
.ht-progress {
  margin-top: 8px;
}
.ht-progress-text {
  display: inline-block;
  margin-top: 2px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
/* 上传用的隐藏 input:用按钮触发,不占版面 */
.ht-file {
  display: none;
}

/* ── 结果卡:占满剩余高度,表格内部滚动 ── */
.ht-result-card {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.ht-result-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.ht-stats {
  flex: none;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 10px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.ht-stats b {
  color: var(--el-text-color-primary);
  font-variant-numeric: tabular-nums;
}
.ht-export {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.ht-table-wrap {
  flex: 1;
  min-height: 0;
}
/* 分页:固定在卡片底部,不随表格滚动 */
.ht-pager {
  flex: none;
  display: flex;
  justify-content: flex-end;
  padding-top: 10px;
}

/* 哈希值:等宽字体便于肉眼比对(大小写/长度一眼看得出) */
.ht-hash {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 12.5px;
  word-break: break-all;
}

.ht-idle {
  flex: none;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}

/* ── 导出弹窗 ── */
.ht-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.ht-names {
  display: flex;
  gap: 8px;
  width: 100%;
}
.ht-filename {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}

/* 窄屏:设置项各占一行 */
@media (max-width: 900px) {
  .ht-export {
    margin-left: 0;
  }
}
</style>
