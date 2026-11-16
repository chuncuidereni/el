<!--
  AccessMigrationView.vue

  职责：权限迁移页面：展示用户权限迁移状态并触发受控迁移操作。
  边界：挂载时并行加载迁移记录和任务，并按历史任务所有者决定可操作项；迁移操作以服务端结果为准，完成后刷新列表。
-->

<template>
  <section class="migration-page">
    <h2>历史数据归属</h2><p>只有明确分配后，历史数据才对指定用户可见。分配不会自动共享给其他用户。</p>
    <el-form label-width="100px">
      <el-form-item label="归属用户"><el-select v-model="owner" filterable><el-option v-for="u in users" :key="u.userId" :value="u.userId!" :label="u.username" /></el-select></el-form-item>
    </el-form>
    <h3>未分配的数据源</h3>
    <el-table :data="sources" border v-loading="loading"><el-table-column prop="name" label="名称" /><el-table-column prop="type" label="类型" /><el-table-column label="操作"><template #default="{ row }"><el-button :disabled="!owner || saving" @click="assign(row)">分配给所选用户</el-button></template></el-table-column></el-table>
    <h3>未分配的任务与工作流</h3>
    <p>同步任务须先分配数据源；任务分配后保持停用，由所有者确认配置后启用。</p>
    <el-select v-model="taskType" @change="loadTasks"><el-option v-for="kind in taskKinds" :key="kind.value" :value="kind.value" :label="kind.label" /></el-select>
    <el-table :data="tasks" border v-loading="tasksLoading"><el-table-column prop="name" label="名称" /><el-table-column label="操作"><template #default="{ row }"><el-button :disabled="!owner || saving" @click="assignTask(row)">分配给所选用户</el-button></template></el-table-column></el-table>
    <h3>未分配的 SQL 查询</h3>
    <p>分配后进入该用户的默认分组；SQL 文本不会执行，也不会授予其中引用表的访问权。</p>
    <el-table :data="sqlQueries" border v-loading="loading"><el-table-column prop="name" label="名称" /><el-table-column label="操作"><template #default="{ row }"><el-button :disabled="!owner || saving" @click="assignSqlQuery(row)">分配 SQL 查询</el-button></template></el-table-column></el-table>
    <h3>登记 ClickHouse 历史表</h3>
    <el-form label-width="100px">
      <el-form-item label="连接"><el-select v-model="table.connectionId"><el-option v-for="c in connections" :key="c.id" :value="c.id" :label="c.name" /></el-select></el-form-item>
      <el-form-item label="数据库"><el-input v-model="table.database" /></el-form-item>
      <el-form-item label="表名"><el-input v-model="table.table" /></el-form-item>
      <el-form-item><el-button type="primary" :loading="saving" :disabled="!owner || !table.connectionId || !table.database || !table.table" @click="register">登记并分配</el-button></el-form-item>
    </el-form>
  </section>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post } from '@/api/http'
import type { AuthMe } from '@/types'
const users = ref<AuthMe[]>([]), sources = ref<{ id: number; name: string; type: string }[]>([]), connections = ref<{ id: number; name: string }[]>([])
const sqlQueries = ref<{ id: number; name: string }[]>([])
const owner = ref<number>(), loading = ref(false), saving = ref(false)
const table = reactive({ connectionId: undefined as number | undefined, database: '', table: '' })
const taskKinds = [{ value: 'sync-task', label: '同步任务' }, { value: 'workflow', label: '节点工作流' }, { value: 'arb-workflow', label: '仲裁工作流' }, { value: 'query-workflow', label: '查询工作流' }]
const taskType = ref('sync-task'), tasks = ref<{ id: number; name: string }[]>([]), tasksLoading = ref(false)
let tasksVersion = 0
/** 加载可迁移的同步任务，作为权限归属迁移的来源列表。 */
async function loadTasks() {
  const version = ++tasksVersion
  tasks.value = []; tasksLoading.value = true
  try { const rows = await get<typeof tasks.value>('/access-migration/tasks', { type: taskType.value }); if (version === tasksVersion) tasks.value = rows }
  catch {} finally { if (version === tasksVersion) tasksLoading.value = false }
}
/** 对单个同步任务执行归属迁移；确认与提示文案保留服务端失败原因。 */
async function assignTask(task: { id: number; name: string }) {
  const selectedOwner = owner.value, selectedType = taskType.value
  const name = users.value.find(u => u.userId === selectedOwner)?.username
  try { await ElMessageBox.confirm(`将「${task.name}」分配给 ${name} 并保持停用？`, '确认任务归属', { type: 'warning' }) } catch { return }
  saving.value = true
  try { await post(`/access-migration/tasks/${selectedType}/${task.id}`, { userId: selectedOwner }); await loadTasks(); ElMessage.success('已分配，待所有者确认后启用') } catch {} finally { saving.value = false }
}
/** 汇总加载迁移页面需要的任务与 SQL 查询数据。 */
async function load() {
  loading.value = true
  try { [users.value, sources.value, connections.value, sqlQueries.value] = await Promise.all([get<AuthMe[]>('/app-users'), get<typeof sources.value>('/access-migration/datasources'), get<typeof connections.value>('/connections'), get<typeof sqlQueries.value>('/access-migration/sql-queries')]); users.value = users.value.filter(u => u.enabled) } catch {} finally { loading.value = false }
}
/** 对单个 SQL 查询执行归属迁移。 */
async function assignSqlQuery(query: { id: number; name: string }) {
  const selectedOwner = owner.value
  if (!selectedOwner || saving.value) return
  const name = users.value.find(u => u.userId === selectedOwner)?.username
  try { await ElMessageBox.confirm(`将「${query.name}」分配给 ${name} 的默认分组？此操作不会执行 SQL。`, '确认 SQL 查询归属', { type: 'warning' }) } catch { return }
  saving.value = true
  try { await post(`/access-migration/sql-queries/${query.id}`, { userId: selectedOwner }); await load(); ElMessage.success('SQL 查询已分配') } catch {} finally { saving.value = false }
}
/** 迁移通用来源的确认入口；成功和失败后都刷新页面数据。 */
async function assign(source: { id: number; name: string }) {
  const selectedOwner = owner.value
  const name = users.value.find(u => u.userId === selectedOwner)?.username
  try { await ElMessageBox.confirm(`将「${source.name}」分配给 ${name}？`, '确认数据归属', { type: 'warning' }) } catch { return }
  saving.value = true
  try { await post(`/access-migration/datasources/${source.id}`, { userId: selectedOwner }); await load(); ElMessage.success('已分配') } catch {} finally { saving.value = false }
}
/** 注册当前选择的历史资源，使其进入新的权限归属体系。 */
async function register() {
  const name = users.value.find(u => u.userId === owner.value)?.username
  try { await ElMessageBox.confirm(`登记 ClickHouse 表「${table.table}」并分配给 ${name}？`, '确认表归属', { type: 'warning' }) } catch { return }
  saving.value = true
  try { await post('/access-migration/tables', { ...table, userId: owner.value }); table.table = ''; ElMessage.success('表已分配') } catch {} finally { saving.value = false }
}
onMounted(load)
onMounted(loadTasks)
</script>
<style scoped>.migration-page { padding: 20px; max-width: 950px; } .el-form { max-width: 520px; } p { color: var(--el-text-color-secondary); }</style>
