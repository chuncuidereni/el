<!--
  ShareTableDialog.vue

  职责：数据共享弹窗：搜索接收者、创建授权并展示当前共享关系。
  边界：按 assetId/tableName 搜索接收者并维护共享关系；打开或资产变化时刷新，变更通过 changed 通知父级重载。
-->

<template>
  <el-dialog
    :model-value="modelValue"
    :title="`共享 · ${tableName}`"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <div class="share-add">
      <el-select v-model="recipient" filterable remote clearable :remote-method="searchRecipients"
        placeholder="选择接收用户，可输入账号名搜索" style="flex: 1" :loading="searching">
        <el-option v-for="u in recipients" :key="u.userId" :value="u.userId" :label="u.username" />
      </el-select>
      <el-button type="primary" :loading="saving" :disabled="!recipient" @click="addShare">共享</el-button>
    </div>

    <el-table :data="shares" v-loading="loading" border stripe size="small">
      <el-table-column prop="username" label="接收用户" min-width="130" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.lastError" class="dim">{{ row.lastError }}</span>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'revoked'" size="small" link type="danger" @click="revoke(row)">撤销</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="dim">尚未共享给任何用户。共享后对方只能读取，不能修改、转授或作为写入目标。</div>
      </template>
    </el-table>
    <p class="share-hint">可选用户仅显示账号名，最多显示 50 人，可搜索更多。共享后，对方在原表列表中看到该表及创建者，只能读取；数据源、任务和 SQL 草稿仍独立。</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dataAssetsApi, type DataAssetShare, type DataAssetRecipient } from '@/api'
import { confirmAction } from '@/utils/confirm'

const props = defineProps<{ modelValue: boolean; assetId: number | null; tableName: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'changed'): void }>()

const shares = ref<DataAssetShare[]>([])
const recipients = ref<DataAssetRecipient[]>([])
const recipient = ref<number | undefined>(undefined)
const loading = ref(false)
const searching = ref(false)
const saving = ref(false)
let searchSeq = 0
let sharesSeq = 0

watch(() => [props.modelValue, props.assetId] as const, ([open]) => {
  ++searchSeq
  ++sharesSeq
  searching.value = false
  loading.value = false
  recipient.value = undefined
  recipients.value = []
  shares.value = []
  if (open && props.assetId) {
    void loadShares()
    void searchRecipients('')
  }
}, { immediate: true })

/** 拉取当前资产的共享列表；弹窗重新打开时应以服务端数据覆盖旧快照。 */
async function loadShares() {
  if (!props.assetId) return
  const seq = ++sharesSeq
  loading.value = true
  try {
    const rows = await dataAssetsApi.shares(props.assetId)
    if (seq === sharesSeq) shares.value = rows
  } catch { /* global error */ }
  finally { if (seq === sharesSeq) loading.value = false }
}

/** 搜索可共享用户；空关键词请求用于打开弹窗时初始化候选列表。 */
async function searchRecipients(keyword = '') {
  const q = keyword.trim()
  const seq = ++searchSeq
  searching.value = true
  try {
    const rows = await dataAssetsApi.recipients(q)
    if (seq === searchSeq) recipients.value = rows
  } catch { /* global error */ }
  finally { if (seq === searchSeq) searching.value = false }
}

/** 校验接收人并创建授权，成功后清空选择并刷新当前授权。 */
async function addShare() {
  if (!props.assetId || !recipient.value) return
  const who = recipients.value.find((r) => r.userId === recipient.value)?.username ?? `用户 ${recipient.value}`
  if (!(await confirmAction('添加共享', `确认把表「${props.tableName}」只读共享给 ${who}？`, '确认共享'))) return
  saving.value = true
  try {
    await dataAssetsApi.share(props.assetId, recipient.value)
    ElMessage.success('已共享，对方可在原表列表看到该表（只读）')
    recipient.value = undefined
    await loadShares()
    emit('changed')
  } catch { /* global error */ }
  finally { saving.value = false }
}

/** 撤销单条共享授权，避免误删其他接收人的权限。 */
async function revoke(row: DataAssetShare) {
  if (!props.assetId) return
  if (!(await confirmAction('撤销共享', `确认撤销 ${row.username} 对表「${props.tableName}」的只读共享？撤销后对方将立即失去访问权。`, '确认撤销'))) return
  try {
    await dataAssetsApi.revoke(props.assetId, row.userId)
    ElMessage.success('已撤销共享')
    await loadShares()
    emit('changed')
  } catch { /* global error */ }
}

/** 把授权状态码转换为用户可读文案；未知状态原样展示便于排障。 */
function statusLabel(s: string) {
  return ({ active: '已生效', pending: '授权中', revoking: '撤权中', revoked: '已撤销', failed: '失败' } as Record<string, string>)[s] || s
}
/** 把授权状态映射为 Element Plus 标签色，未知状态使用中性样式。 */
function statusType(s: string): 'success' | 'warning' | 'danger' | 'info' {
  if (s === 'active') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'revoked') return 'info'
  return 'warning'
}
</script>

<style scoped>
.share-add { display: flex; gap: 8px; margin-bottom: 14px; }
.share-hint { color: var(--el-text-color-secondary); font-size: 12px; margin: 10px 0 0; }
.dim { color: var(--el-text-color-secondary); }
</style>
