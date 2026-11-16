<!--
  SharedDataView.vue

  职责：我的数据与共享页面：注册、共享、撤销共享并预览受控数据资产。
  边界：加载当前账号的数据资产和共享关系，注册、共享或撤销后刷新；资产预览范围由后端权限决定。
-->

<template>
  <section class="assets-page">
    <header><div><h2>我的数据与共享</h2><p>数据默认私有；共享接收者只能预览，不能修改、导出或再次共享。</p></div><el-button v-if="scope === 'mine'" type="primary" @click="openRegister">添加源表</el-button></header>
    <el-radio-group v-model="scope" @change="load"><el-radio-button value="mine">我的表</el-radio-button><el-radio-button value="shared">共享给我的</el-radio-button></el-radio-group>
    <el-table :data="assets" v-loading="loading" border stripe scrollbar-always-on>
      <el-table-column label="表名"><template #default="{ row }">{{ row.table_name === '__file__' ? '上传文件' : row.table_name }}</template></el-table-column>
      <el-table-column prop="database_name" label="数据库" />
      <el-table-column prop="owner_name" label="所有者" />
      <el-table-column label="权限"><template #default>{{ scope === 'mine' ? '所有者' : '只读共享' }}</template></el-table-column>
      <el-table-column label="操作" width="210"><template #default="{ row }"><el-button text type="primary" @click="preview(row)">预览</el-button><el-button v-if="scope === 'mine'" text @click="openShares(row)">共享管理</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="registerVisible" title="添加自己的数据源表" width="500px">
      <el-form label-width="80px">
        <el-form-item label="数据源"><el-select v-model="sourceId" filterable @change="loadTables"><el-option v-for="s in sources" :key="s.id" :value="s.id" :label="s.name" /></el-select></el-form-item>
        <el-form-item label="表"><el-select v-model="sourceTable" filterable :loading="tablesLoading"><el-option v-for="t in sourceTables" :key="t" :value="t" :label="t === '__file__' ? '上传文件' : t" /></el-select></el-form-item>
      </el-form>
      <p>支持 MySQL、PostgreSQL 和已上传文件。ClickHouse 历史表由管理员分配归属。</p>
      <template #footer><el-button @click="registerVisible = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!sourceId || !sourceTable" @click="register">添加</el-button></template>
    </el-dialog>
    <el-dialog v-model="sharesVisible" title="只读共享" width="570px" @closed="selectedUser = undefined">
      <p>{{ selectedAsset?.table_name }} · 新增表不会自动共享</p>
      <div class="share-add"><el-select v-model="selectedUser" filterable remote :remote-method="searchUsers" placeholder="输入至少两个字符搜索用户名" :loading="searching"><el-option v-for="u in recipients" :key="u.userId" :value="u.userId" :label="u.username" /></el-select><el-button type="primary" :disabled="!selectedUser" :loading="saving" @click="share">添加共享</el-button></div>
      <el-table :data="grants" border size="small"><el-table-column prop="username" label="接收用户" /><el-table-column label="权限"><template #default>只读预览</template></el-table-column><el-table-column label="操作"><template #default="{ row }"><el-button text type="danger" :disabled="saving" @click="revoke(row)">撤销</el-button></template></el-table-column></el-table>
    </el-dialog>
    <el-dialog v-model="previewVisible" title="数据预览（最多 100 行）" width="85%">
      <el-table :data="previewRows" v-loading="previewLoading" border max-height="480"><el-table-column v-for="(name, i) in previewColumns" :key="i" :prop="String(i)" :label="name" min-width="140" show-overflow-tooltip /></el-table>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, del } from '@/api/http'
import type { DataSource } from '@/types'
interface Asset { id: number; backend_type: string; backend_id: number; database_name: string; table_name: string; owner_name: string }
interface User { userId: number; username: string }
const scope = ref('mine'), assets = ref<Asset[]>([]), loading = ref(false), saving = ref(false)
const sources = ref<DataSource[]>([]), sourceId = ref<number>(), sourceTable = ref(''), sourceTables = ref<string[]>([]), tablesLoading = ref(false), registerVisible = ref(false)
const sharesVisible = ref(false), selectedAsset = ref<Asset>(), recipients = ref<User[]>([]), grants = ref<User[]>([]), selectedUser = ref<number>(), searching = ref(false)
const previewVisible = ref(false), previewLoading = ref(false), previewColumns = ref<string[]>([]), previewRows = ref<Record<string, unknown>[]>([])
/** 按“我的/共享给我”范围重新加载资产；切换范围时旧列表先保留到请求结束。 */
async function load() { loading.value = true; try { assets.value = await get('/data-assets', { scope: scope.value }) } catch {} finally { loading.value = false } }
/** 加载可注册的数据源并重置注册表单，文件型数据源在下一步使用固定虚拟表名。 */
async function openRegister() {
  try { sources.value = (await get<DataSource[]>('/datasources')).filter(s => ['mysql', 'pgsql', 'csv', 'json', 'xlsx'].includes(s.type)); sourceId.value = undefined; sourceTable.value = ''; sourceTables.value = []; registerVisible.value = true } catch {}
}
/** 根据所选数据源加载可注册表；文件型来源不请求远端表清单。 */
async function loadTables() {
  sourceTable.value = ''; sourceTables.value = []; tablesLoading.value = true
  const id = sourceId.value
  try {
    const source = sources.value.find(s => s.id === id)
    const result = source && ['csv', 'json', 'xlsx'].includes(source.type) ? ['__file__'] : (await get<{ tables: string[] }>(`/datasources/${id}/sql-tables`)).tables
    if (sourceId.value === id) sourceTables.value = result
  } catch {} finally { tablesLoading.value = false }
}
/** 把选中的来源表登记为当前用户资产，成功后关闭弹窗并刷新列表。 */
async function register() {
  const tableLabel = sourceTable.value === '__file__' ? '上传文件' : sourceTable.value
  try { await ElMessageBox.confirm(`确认登记数据资产「${tableLabel}」？登记后默认仅自己可见。`, '添加源表', { type: 'warning' }) } catch { return }
  saving.value = true
  try { await post('/data-assets', { datasourceId: sourceId.value, table: sourceTable.value }); registerVisible.value = false; await load(); ElMessage.success('已添加，默认仅自己可见') } catch {} finally { saving.value = false }
}
/** 切换到指定资产的共享视图，先清空上一资产的授权和接收人状态。 */
async function openShares(asset: Asset) { selectedAsset.value = asset; grants.value = []; recipients.value = []; selectedUser.value = undefined; sharesVisible.value = true; await loadShares() }
/** 拉取当前资产的授权列表；没有选中资产时不发请求。 */
async function loadShares() { if (selectedAsset.value) { try { grants.value = await get(`/data-assets/${selectedAsset.value.id}/shares`) } catch {} } }
let searchVersion = 0
/** 按关键词搜索可接收共享的用户，调用方负责控制防抖或触发时机。 */
async function searchUsers(keyword: string) {
  const version = ++searchVersion; searching.value = true
  try { const found = await get<User[]>('/data-assets/recipients', { keyword }); if (version === searchVersion) recipients.value = found } catch {} finally { if (version === searchVersion) searching.value = false }
}
/** 将当前资产授权给选中用户，并刷新授权列表以服务器结果为准。 */
async function share() {
  if (!selectedAsset.value || !selectedUser.value || saving.value) return
  const who = recipients.value.find(u => u.userId === selectedUser.value)?.username ?? `用户 ${selectedUser.value}`
  try { await ElMessageBox.confirm(`确认把「${selectedAsset.value.table_name || selectedAsset.value.database_name}」共享给 ${who}？对方只能预览。`, '添加共享', { type: 'warning' }) } catch { return }
  saving.value = true
  try { await post(`/data-assets/${selectedAsset.value.id}/shares`, { userId: selectedUser.value }); selectedUser.value = undefined; await loadShares(); ElMessage.success('已共享，只允许预览') } catch {} finally { saving.value = false }
}
/** 撤销指定用户对当前资产的访问；共享权限不可借此扩展为修改权限。 */
async function revoke(user: User) {
  try { await ElMessageBox.confirm(`撤销对 ${user.username} 的共享？`, '撤销共享', { type: 'warning' }) } catch { return }
  saving.value = true
  try { await del(`/data-assets/${selectedAsset.value!.id}/shares/${user.userId}`); await loadShares(); ElMessage.success('已撤销') } catch {} finally { saving.value = false }
}
/** 读取资产预览数据；共享接收者只能查看，不暴露编辑或导出流程。 */
async function preview(asset: Asset) {
  previewColumns.value = []; previewRows.value = []; previewVisible.value = true; previewLoading.value = true
  try {
    const data = await post<{ columns: string[]; rows: unknown[][] }>(`/data-assets/${asset.id}/preview`, { offset: 0 })
    previewColumns.value = data.columns
    previewRows.value = data.rows.map(row => Object.fromEntries(row.map((value, i) => [String(i), value])))
  } catch {} finally { previewLoading.value = false }
}
onMounted(load)
</script>
<style scoped>
.assets-page { padding: 20px; } header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
h2 { margin: 0; } p { color: var(--el-text-color-secondary); } .el-table { margin-top: 18px; } .share-add { display: flex; gap: 12px; }
</style>
