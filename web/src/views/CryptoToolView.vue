<!--
  CryptoToolView.vue

  职责：加密工具页面：提交待处理文本并展示算法结果。
  边界：按后端算法规格生成密钥、分批执行文本加解密并分页展示；结果变化重置分页，算法和大小写偏好写入 localStorage。
-->

<template>
  <div class="crypto-tool-view">
    <div class="page-head">
      <div>
        <h2>加密解密</h2>
        <p class="page-sub">
          批量加解密,每行一条。AES / SM4(国密)/ 3DES,支持 CBC / GCM / ECB。
          密钥与 IV 的长度按算法校验(给错会说清该多少字节);GCM 自带完整性校验,解不出来就是密钥或密文不对。
          ⚠️ 本页是明文传输到本机后端的,别拿它当密钥保管工具。
        </p>
      </div>
    </div>

    <!-- ── 参数卡 ── -->
    <el-card shadow="never" class="cr-card">
      <div class="cr-form">
        <!--
          方向/算法都用按钮组而不是下拉:值少、来回切得频繁,点一下即选。
          (同类页面的经验:这一页的 el-select 会触发 EP 的递归更新告警,少一个是一个。)
        -->
        <el-radio-group v-model="direction" aria-label="方向">
          <el-radio-button value="encrypt">加密</el-radio-button>
          <el-radio-button value="decrypt">解密</el-radio-button>
        </el-radio-group>

        <!-- 算法列表由后端下发(specs):加解密实现都在服务端,前端不能自己写死一份 -->
        <el-select v-model="algo" class="cr-sel" aria-label="算法">
          <el-option v-for="s in specs" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>

        <!-- 密钥格式影响的是"怎么解析这串文本",所以必须和密钥输入框挨着放 -->
        <el-select v-model="keyFormat" class="cr-sel-sm" aria-label="密钥格式">
          <el-option label="密钥:HEX" value="hex" />
          <el-option label="密钥:Base64" value="base64" />
          <el-option label="密钥:文本" value="text" />
        </el-select>

        <el-button @click="makeKey">生成密钥</el-button>
      </div>

      <!-- 算法相关的注意事项由后端下发(如"GCM 自带完整性校验"),不在前端复述一遍 -->
      <el-alert v-if="spec?.hint" :title="spec.hint" type="warning" :closable="false" class="cr-hint" />

      <div class="cr-keyrow">
        <el-input v-model="keyInput" :placeholder="keyPlaceholder" aria-label="密钥" />
      </div>
      <!-- IV 只在需要它的模式(非 GCM)出现:placeholder 里直接说明该填多少字节 -->
      <div v-if="needsIv" class="cr-keyrow">
        <el-input v-model="ivInput" :placeholder="ivPlaceholder" aria-label="IV" />
      </div>

      <!-- 生成结果:hex 与 base64 都给,方便直接填进对方系统 -->
      <div v-if="gen" class="cr-gen">
        <div>已生成 <b>{{ gen.algoName }}</b> 的密钥({{ gen.keyLen }} 字节<template v-if="gen.ivLen">,IV {{ gen.ivLen }} 字节</template>):</div>
        <div class="cr-genrow"><span>KEY(hex)</span><code>{{ gen.keyHex }}</code><el-button link size="small" @click="copy(gen.keyHex)">复制</el-button></div>
        <div class="cr-genrow"><span>KEY(base64)</span><code>{{ gen.keyB64 }}</code><el-button link size="small" @click="copy(gen.keyB64)">复制</el-button></div>
        <div v-if="gen.ivHex" class="cr-genrow"><span>IV(hex)</span><code>{{ gen.ivHex }}</code><el-button link size="small" @click="copy(gen.ivHex)">复制</el-button></div>
        <div v-if="gen.keyNotice" class="cr-note">{{ gen.keyNotice }}</div>
      </div>

      <!-- 解密时的输入编码 / 加密时的输出编码 -->
      <!-- 二选一而不是都给:加/解密只会有其中一种编码在起作用,同时摆两组单选框会让人以为要填两处 -->
      <div class="cr-form cr-form-2">
        <template v-if="direction === 'decrypt'">
          <span class="cr-label">密文编码</span>
          <el-radio-group v-model="inFormat">
            <el-radio-button value="base64">Base64</el-radio-button>
            <el-radio-button value="hex">HEX</el-radio-button>
          </el-radio-group>
        </template>
        <template v-else>
          <span class="cr-label">输出编码</span>
          <el-radio-group v-model="outFormat">
            <el-radio-button value="base64">Base64</el-radio-button>
            <el-radio-button value="hex">HEX</el-radio-button>
          </el-radio-group>
        </template>
      </div>
    </el-card>

    <!-- ── 数据卡 ── -->
    <el-card shadow="never" class="cr-card">
      <el-input
        v-model="raw"
        type="textarea"
        :rows="5"
        resize="vertical"
        :placeholder="direction === 'encrypt' ? '每行一条待加密的明文,例如：helloworld' : '每行一条待解密的密文(base64 或 hex)'"
        aria-label="待处理的数据"
      />
      <div class="cr-run">
        <el-button type="primary" :loading="loading" @click="run">立即执行</el-button>
        <el-button @click="pickFile">上传文件</el-button>
        <!-- 清空同时清结果:留着上一次的结果会让人以为它属于新输入 -->
        <el-button text :disabled="!raw" @click="clearAll">清空</el-button>
        <span class="cr-count">{{ values.length }} 行</span>
        <!-- 隐藏的原生 file input:样式无法定制,所以按钮只负责 .click() 转发 -->
        <input ref="fileRef" type="file" accept=".txt,.csv,text/plain" class="cr-file" @change="onFile" />
      </div>

      <!-- 进度条只在需要分批(>CHUNK 行)时出现;见 run 里的分批说明 -->
      <div v-if="progressTotal > CHUNK" class="cr-progress">
        <el-progress
          :percentage="Math.round((progressDone / progressTotal) * 100)"
          :stroke-width="10"
          :status="progressDone >= progressTotal ? 'success' : undefined"
        />
        <span class="cr-count">{{ progressDone }} / {{ progressTotal }} 条</span>
      </div>
    </el-card>

    <!-- ── 结果卡 ── -->
    <el-card v-if="result" shadow="never" class="cr-card">
      <div class="cr-stats">
        <span>{{ result.algo.toUpperCase() }}</span>
        <span>{{ result.direction === 'encrypt' ? '加密' : '解密' }}</span>
        <span>共 <b>{{ result.count }}</b> 条</span>
        <!-- 失败数只在非 0 时出现:正常情况不该有这一项占位 -->
        <span v-if="result.failCount" class="cr-fail">失败 <b>{{ result.failCount }}</b> 条</span>
        <span class="cr-export"><el-button size="small" @click="openExport">导出</el-button></span>
      </div>

      <!-- 前端分页 + max-height:几十万行不能一次全渲染(执行是全量累加的,渲染不是) -->
      <el-table :data="pagedItems" border stripe size="small" max-height="380">
        <el-table-column prop="value" label="原值" min-width="180" show-overflow-tooltip />
        <el-table-column label="结果" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 单行错误占结果列:失败原因就贴在该行上,比另开一列更短、更好对 -->
            <span v-if="row.error" class="cr-err">{{ row.error }}</span>
            <span v-else>{{ row.result }}</span>
          </template>
        </el-table-column>
        <!-- 失败行没有结果可复制,按钮跟着不出现(不是禁用:禁用的按钮会让人以为需要什么条件) -->
        <el-table-column label="" width="80" align="center">
          <template #default="{ row }">
            <el-button v-if="!row.error" link size="small" @click="copy(row.result)">复制</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="cr-pager">
        <el-pagination
          background
          layout="total, prev, pager, next, jumper"
          :total="result.items.length"
          :current-page="page"
          :page-size="pageSize"
          @current-change="(p: number) => (page = p)"
        />
      </div>
    </el-card>

    <el-card v-else shadow="never" class="cr-card cr-idle">
      填好参数与数据后点「立即执行」。解密时一行失败不影响其它行,错误会显示在那一行上。
    </el-card>

    <!--
      导出选项:与哈希计算【同一套】(内容 / 表头 / 列名 / 文件名),
      用户不用为两个工具学两遍。唯一多出来的是"失败行"的处理,见 buildExport。
    -->
    <el-dialog v-model="exportVisible" title="导出结果" width="480px" :close-on-click-modal="false">
      <el-form label-width="88px" label-position="left">
        <el-form-item label="导出内容">
          <el-radio-group v-model="exportOpts.withValues">
            <el-radio :value="true">原值 + 结果</el-radio>
            <el-radio :value="false">仅结果</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="表头">
          <el-switch v-model="exportOpts.withHeader" />
          <span class="cr-hint-inline">第一行输出列名</span>
        </el-form-item>

        <!-- 列名只在有表头时可填;「仅结果」时只有一列 -->
        <el-form-item v-if="exportOpts.withHeader && exportOpts.withValues" label="列名">
          <div class="cr-names">
            <el-input v-model="exportOpts.valueName" size="small" placeholder="原值列名" />
            <el-input v-model="exportOpts.resultName" size="small" placeholder="结果列名" />
          </div>
        </el-form-item>
        <el-form-item v-else-if="exportOpts.withHeader" label="列名">
          <el-input v-model="exportOpts.resultName" size="small" placeholder="结果列名" />
        </el-form-item>

        <el-form-item label="文件名">
          <span class="cr-filename">{{ exportFilename }}</span>
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
 * 工具 · 加密解密。
 *
 * 与哈希计算同一套交互(批量 / 每行一条 / 分批 + 进度 / 分页 / 导出),只有一处刻意的不同:
 * 加解密【按行返回结果与错误】—— 一批密文里有两行密钥不对是常态,
 * 不能让它们把另外几百行也毁掉(哈希计算那种"一条失败整批失败"在这里不适用)。
 *
 * 界面上要如实告诉用户的两件事:
 *  1. CBC / ECB 都没有完整性校验 → 密钥错时会解出一段"看起来正常"的垃圾,
 *     所以提示里写清了,GCM 那句则相反(解不出来就是密钥不对)。
 *  2. 密钥/IV 的长度按算法即时提示(给错会在提交前就能看出该多少字节)。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { cryptoToolApi } from '@/api'
import type { CryptoToolKeyPair, CryptoToolResult, CryptoToolSpec } from '@/types'
import { CSV_BOM, csvRow, downloadBlob } from '@/utils/download'

/** 每次请求的条数(与哈希计算同一个口径:请求体/响应体不集中成一个大包,且能刷帧出进度) */
const CHUNK = 5000
/** 结果表每页条数。结果可能几十万条,全部塞进 el-table 会直接卡死渲染 */
const pageSize = 100

// 算法清单来自后端(见 onMounted);在拿到之前下面的 spec 为空,占位文案会退化成"密钥/IV"
const specs = ref<CryptoToolSpec[]>([])
const algo = ref('aes-256-cbc')
const direction = ref<'encrypt' | 'decrypt'>('encrypt')
const keyFormat = ref<'hex' | 'base64' | 'text'>('hex')
const inFormat = ref<'base64' | 'hex'>('base64')
const outFormat = ref<'base64' | 'hex'>('base64')
const keyInput = ref('')
const ivInput = ref('')
/** 最近一次「生成密钥」的结果:展示用(hex/base64 都给),不参与提交 */
const gen = ref<CryptoToolKeyPair | null>(null)
const raw = ref('')
const loading = ref(false)
const result = ref<CryptoToolResult | null>(null)
const page = ref(1)
const progressDone = ref(0)
const progressTotal = ref(0)
const fileRef = ref<HTMLInputElement>()

/** 导出选项弹窗(与哈希计算同一套选项,用户不用学第二遍) */
const exportVisible = ref(false)
const exportOpts = ref({
  withValues: true,
  withHeader: true,
  valueName: '原值',
  resultName: '结果'
})

/** 导出文件名:随内容形式变化(csv 带原值、txt 只有结果) */
const exportFilename = computed(
  () => `crypto-${algo.value}.${exportOpts.value.withValues ? 'csv' : 'txt'}`
)

/** 当前算法规格(密钥/IV 长度、提示语都从这里取) */
const spec = computed(() => specs.value.find((s) => s.id === algo.value))
/** GCM 不用填 IV(每次自动生成并带在密文里);其余带 IV 的模式才显示 IV 输入框 */
const needsIv = computed(() => (spec.value?.ivLen ?? 0) > 0)

// 占位文案直接写清"该多少字节":密钥长度给错是最常见的失败原因,
// 与其等提交后报错,不如在输入前就说清楚。3DES 额外允许 16 字节是它自身的规格。
const keyPlaceholder = computed(() =>
  spec.value ? `密钥(${spec.value.keyLen} 字节;3DES 也可给 16 字节)` : '密钥'
)
const ivPlaceholder = computed(() => (spec.value ? `IV(${spec.value.ivLen} 字节)` : 'IV'))

/**
 * 输入 → 待处理列表。每行一条:
 *  · /\r?\n/ 同时兼容 CRLF(从 Windows 记事本/Excel 粘过来就是 CRLF)
 *  · trim 是必须的 —— 密文里混进一个不可见空格就解不出来,报错还很难查
 *  · 丢掉空行(Boolean 过滤),末尾换行不会算成一条空数据
 */
const values = computed(() =>
  raw.value
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean)
)
const pagedItems = computed(() => {
  const start = (page.value - 1) * pageSize
  return (result.value?.items ?? []).slice(start, start + pageSize)
})
// 换了一批结果就回到第一页:否则上一次停在第 5 页、这次只有 1 页,会看到一张空表
watch(result, () => {
  page.value = 1
})

function clearAll() {
  raw.value = ''
  // 结果一并清掉:输入与结果不同源会让人把上一次的结果当成这一次的
  result.value = null
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    // 剪贴板 API 在非 https / 无权限时会被拒 —— 这里必须给可操作的替代方案
    ElMessage.error('浏览器不允许自动复制,请手动选择文本')
  }
}

async function makeKey() {
  try {
    const kp = await cryptoToolApi.keygen(algo.value)
    gen.value = kp
    // 生成的密钥是 hex:把格式切过去,否则用户以为填好了其实解析不出来
    keyInput.value = kp.keyHex
    ivInput.value = kp.ivHex ?? ''
    keyFormat.value = 'hex'
  } catch {
    /* http 拦截器已统一提示 */
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
    // 直接替掉输入框内容(不是追加):上传的语义是"用这个文件作为本批数据"
    raw.value = await file.text()
  } catch {
    ElMessage.error('读取文件失败')
  } finally {
    // 读完清空 value:否则再选同一个文件不会触发 change,看起来像"点了没反应"
    input.value = ''
  }
}

async function run() {
  const vals = values.value
  if (!vals.length) {
    ElMessage.warning('请先输入要处理的数据(每行一个)')
    return
  }
  if (!keyInput.value.trim()) {
    ElMessage.warning('请先填密钥(或点「生成密钥」)')
    return
  }
  loading.value = true
  progressTotal.value = vals.length
  progressDone.value = 0
  const items: CryptoToolResult['items'] = []
  let okCount = 0
  let failCount = 0
  try {
    // 分批请求:每批的结果按顺序追加,所以最终顺序与输入一致(用户看到的原值列不会乱序)
    for (let i = 0; i < vals.length; i += CHUNK) {
      const part = await cryptoToolApi.run({
        algo: algo.value,
        direction: direction.value,
        key: keyInput.value,
        iv: ivInput.value,
        keyFormat: keyFormat.value,
        inFormat: inFormat.value,
        outFormat: outFormat.value,
        values: vals.slice(i, i + CHUNK)
      })
      for (const it of part.items) items.push(it)
      okCount += part.okCount
      failCount += part.failCount
      progressDone.value = Math.min(i + CHUNK, vals.length)
      // 让出一帧再发下一批:同步的循环会把主线程占满,进度条根本刷不出来
      if (i + CHUNK < vals.length) await new Promise((r) => setTimeout(r, 0))
    }
    result.value = {
      items,
      algo: algo.value,
      direction: direction.value,
      // 把本次用的编码一起存进结果:结果表/导出标题要按当时的编码来解释,而不是按"现在选中的"
      outFormat: outFormat.value,
      inFormat: inFormat.value,
      count: items.length,
      okCount,
      failCount
    }
    if (failCount) ElMessage.warning(`${items.length} 条里有 ${failCount} 条失败,失败原因见对应行`)
  } catch {
    // 整批级失败(参数错、请求过大):拦截器已提示;结果清掉免得和参数对不上
    result.value = null
  } finally {
    // 无论成败都收掉进度条,否则失败后进度条会永远停在半路
    loading.value = false
    progressTotal.value = 0
    progressDone.value = 0
  }
}

/**
 * 拼导出内容。与哈希计算同一套规则:
 *   · 「原值 + 结果」→ CSV(带 BOM,Excel 打开中文不乱码)
 *   · 「仅结果」    → TXT(不加 BOM:它是喂给别的程序的,多三个字节可能被当非法字符)
 * 多出来的只有"失败行"这一件事,两种模式分别处理:
 */
function buildExport(): { text: string; filename: string; mime: string } {
  const items = result.value?.items ?? []
  const o = exportOpts.value
  const valueName = o.valueName.trim()
  const resultName = o.resultName.trim()
  const lines: string[] = []

  if (o.withValues) {
    if (o.withHeader && (valueName || resultName)) lines.push(csvRow([valueName, resultName]))
    for (const it of items) {
      // 失败行:把错误写进【结果列】,不另开一列 —— 这样和表格里看到的完全一致,
      // 列数也仍与哈希计算的导出一样是两列(加一列会让"同一套导出"变成另一套)
      lines.push(csvRow([it.value, it.result ?? it.error ?? '']))
    }
    return {
      text: CSV_BOM + lines.join('\n') + '\n',
      filename: exportFilename.value,
      mime: 'text/csv;charset=utf-8'
    }
  }

  if (o.withHeader && resultName) lines.push(resultName)
  for (const it of items) {
    // 「仅结果」是拿去喂别的程序的:失败行【跳过】,混进去一行错误文本没有意义。
    // 跳了多少条在导出后的提示里说清(不能静默丢)。
    if (it.error) continue
    lines.push(it.result ?? '')
  }
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
  const failed = items.filter((i) => i.error).length
  if (!exportOpts.value.withValues && failed) {
    // 静默丢数据是最不能接受的:跳过了几条必须说出来
    ElMessage.success(`已导出 ${items.length - failed} 条,跳过 ${failed} 条失败行`)
  } else {
    ElMessage.success(`已导出 ${items.length} 条`)
  }
}

onMounted(async () => {
  try {
    const c = await cryptoToolApi.config()
    if (c.algos?.length) specs.value = c.algos
  } catch {
    /* 取不到就留空,下面会用默认值提示 */
  }
})
</script>

<style scoped>
/* ── 页面骨架:与 HashToolView / DdlCheckView 等页面逐字对齐 ── */
.crypto-tool-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px 24px;
  box-sizing: border-box;
  overflow: auto;
}
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
/* flex:none:三张卡按内容高度排列,整页滚动(不让卡片被压缩) */
.cr-card {
  flex: none;
}
.cr-card :deep(.el-card__body) {
  padding: 14px 16px;
}
.cr-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap; /* 窄屏时参数控件折行,不横向溢出 */
}
.cr-form-2 {
  margin-top: 10px;
}
/* 算法名较长(AES-256-CBC / SM4-CBC),给宽一点;密钥格式短,窄一点 */
.cr-sel {
  width: 200px;
}
.cr-sel-sm {
  width: 150px;
}
.cr-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.cr-hint {
  margin: 10px 0 0;
}
.cr-keyrow {
  margin-top: 8px;
}
/* 生成的密钥块:淡底区分"这是系统产出",而不是用户输入区 */
.cr-gen {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12.5px;
}
.cr-genrow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.cr-genrow span {
  flex: none;
  width: 78px;
  color: var(--el-text-color-secondary);
}
/* 密钥串必须能整段看完(复制要选全),所以换行而不是省略 */
.cr-genrow code {
  font-family: var(--el-font-family-mono, monospace);
  word-break: break-all;
  color: var(--el-text-color-primary);
}
.cr-note {
  margin-top: 6px;
  color: var(--el-color-warning);
}
.cr-run {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
/* 行数靠右:它只是信息,不参与操作(与左侧按钮群分开) */
.cr-count {
  margin-left: auto;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
/* 隐藏原生 file input:样式不可定制,由「上传文件」按钮代为触发 */
.cr-file {
  display: none;
}
.cr-progress {
  margin-top: 8px;
}
/* 结果统计行:各段等距排开,导出按钮用 margin-left:auto 推到最右 */
.cr-stats {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.cr-fail {
  color: var(--el-color-danger);
}
.cr-export {
  margin-left: auto;
}
.cr-err {
  color: var(--el-color-danger);
}
.cr-pager {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}
.cr-idle {
  color: var(--el-text-color-secondary);
}
/* 导出弹窗:与哈希计算的同名元素同一套样式 */
.cr-hint-inline {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
/* 两个列名输入框平分一行 */
.cr-names {
  display: flex;
  gap: 8px;
  width: 100%;
}
/* 文件名是只读预览(随选项自动变),等宽字体便于看清后缀 */
.cr-filename {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
