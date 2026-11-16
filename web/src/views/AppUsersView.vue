<!--
  AppUsersView.vue

  职责：应用用户管理页面：维护账号、角色、状态及管理员操作。
  边界：加载平台用户并按搜索、角色和分页维护列表；新增、编辑、删除经弹窗和 API 完成后刷新，可见操作由当前账号权限限制。
-->

<template>
  <section class="users-page">
    <div class="users-header"><div><h2>平台用户管理</h2><p>内置 admin 按配置的 CK 业务账号权限查看库表；普通用户只看自己的表和获共享的表。</p></div><el-button type="primary" @click="openCreate">新增用户</el-button></div>
    <el-table :data="users" v-loading="loading" border stripe scrollbar-always-on>
      <el-table-column prop="username" label="用户名" />
      <el-table-column label="角色"><template #default="{ row }">{{ row.role === 'admin' ? '管理员' : '普通用户' }}</template></el-table-column>
      <el-table-column label="状态"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '禁用' }}</el-tag><el-tag v-if="row.mustChangePassword" type="warning">待修改密码</el-tag></template></el-table-column>
      <el-table-column label="CK 账号" min-width="240">
        <template #default="{ row }">
          <template v-if="row.ckBindings && row.ckBindings.length">
            <div v-for="b in row.ckBindings" :key="b.bindingId" class="ck-binding">
              <el-tag :type="ckTag(b.status)" size="small">{{ ckLabel(b.status) }}</el-tag>
              <span class="mono">{{ b.ckUsername }}</span>
              <el-tooltip v-if="b.lastError" :content="b.lastError" placement="top">
                <el-icon class="ck-err"><Warning /></el-icon>
              </el-tooltip>
              <el-button v-if="b.status !== 'active'" :disabled="!row.enabled" size="small" link type="primary" :loading="busy.has(b.bindingId)" @click="retryBinding(row, b)">{{ b.status === 'migration-required' ? '更新绑定' : '重试' }}</el-button>
            </div>
          </template>
          <el-button v-else size="small" link type="primary" :disabled="!row.enabled" :loading="busy.has(-row.userId!)" @click="initBindings(row)">初始化 CK 账号</el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260"><template #default="{ row }">
        <el-button size="small" @click="openEdit(row)">编辑</el-button>
        <el-button size="small" @click="openReset(row)">重置密码</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="mode === 'create' ? '新增平台用户' : mode === 'reset' ? '重置密码' : '编辑用户'" width="470px" destroy-on-close @closed="form.password = ''">
      <el-form label-width="90px" @submit.prevent="save">
        <el-form-item label="用户名"><el-input v-model="form.username" :disabled="mode !== 'create'" maxlength="100" autocomplete="off" /></el-form-item>
        <template v-if="mode !== 'reset'">
          <el-form-item label="角色"><el-select v-model="form.role"><el-option value="user" label="普通用户" /><el-option value="admin" label="管理员" /></el-select></el-form-item>
          <el-form-item v-if="mode === 'edit'" label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        </template>
        <el-form-item v-if="mode !== 'edit'" label="初始密码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="请输入密码" /></el-form-item>
        <p v-if="mode !== 'edit'">用户下次登录时需要修改密码。</p>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Warning } from '@element-plus/icons-vue'
import { get, post, put } from '@/api/http'
import type { AuthMe, AppRole } from '@/types'
import { confirmAction } from '@/utils/confirm'

/** 平台用户与真实 CK 账号的绑定摘要(不含凭据)。 */
interface CkBinding {
  bindingId: number
  instanceId: number
  instanceName?: string
  ckUsername: string
  status: string
  lastError?: string | null
  lastVerifiedAt?: string | null
  credentialVersion?: number
}
interface AppUserRow extends AuthMe { userId: number; ckBindings?: CkBinding[] }

const users = ref<AppUserRow[]>([])
const busy = ref<Set<number>>(new Set())
const loading = ref(false), saving = ref(false), visible = ref(false)
const mode = ref<'create' | 'edit' | 'reset'>('create')
const form = reactive({ userId: 0, username: '', role: 'user' as AppRole, enabled: true, password: '' })
/** 拉取用户列表；加载标志只保护本次列表请求，失败交给全局 HTTP 层提示。 */
async function load() {
  loading.value = true
  try { users.value = await get<AppUserRow[]>('/app-users') } catch { /* global error */ }
  finally { loading.value = false }
}
/** 重置为新增模式并清空密码，防止上一次编辑的敏感值残留。 */
function openCreate() { mode.value = 'create'; Object.assign(form, { userId: 0, username: '', role: 'user', enabled: true, password: '' }); visible.value = true }
/** 复制用户行到编辑表单；密码永远留空，表示不修改现有凭据。 */
function openEdit(row: AppUserRow) { mode.value = 'edit'; Object.assign(form, row, { password: '' }); visible.value = true }
/** 复用编辑弹窗，但把模式改为重置密码，以便提交时走独立语义。 */
function openReset(row: AppUserRow) { openEdit(row); mode.value = 'reset' }
/** 校验角色、密码字节数和保存中的重入，再按新增、编辑或重置分支提交。 */
async function save() {
  if (saving.value) return
  if (!form.username.trim()) { ElMessage.warning('请输入用户名'); return }
  if (mode.value !== 'edit' && (!form.password || new TextEncoder().encode(form.password).length > 72)) { ElMessage.warning('密码不能为空，UTF-8 编码不超过 72 字节'); return }
  const tip = mode.value === 'create'
    ? `确认新建平台用户「${form.username}」(${form.role === 'admin' ? '管理员' : '普通用户'})？`
    : mode.value === 'reset'
      ? `确认重置用户「${form.username}」的登录密码？对方需要重新登录。`
      : `确认保存对用户「${form.username}」的修改(${form.enabled ? '启用' : '禁用'} / ${form.role === 'admin' ? '管理员' : '普通用户'})？`
  if (!(await confirmAction(mode.value === 'create' ? '新建平台用户' : mode.value === 'reset' ? '重置密码' : '保存用户', tip, '确认'))) return
  saving.value = true
  try {
    if (mode.value === 'create') await post('/app-users', { username: form.username, role: form.role, password: form.password })
    else if (mode.value === 'reset') await put(`/app-users/${form.userId}/password`, { password: form.password })
    else await put(`/app-users/${form.userId}`, { role: form.role, enabled: form.enabled })
    visible.value = false; form.password = ''; ElMessage.success('已保存'); await load()
  } catch { /* global error */ }
  finally { saving.value = false }
}
function ckLabel(status: string): string {
  return ({ active: '已绑定', pending: '初始化中', failed: '初始化失败', disabling: '禁用中', disabled: '已禁用', 'migration-required': '待更新绑定' } as Record<string, string>)[status] || status
}
function ckTag(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'active') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'disabled') return 'info'
  return 'warning'
}
/** 为尚无 CK 绑定的用户初始化账号映射；逐用户 busy 状态避免重复点击。 */
async function initBindings(row: AppUserRow) {
  const key = -row.userId!
  if (busy.value.has(key)) return
  if (!(await confirmAction('初始化 CK 账号', `确认为用户「${row.username}」初始化绑定 ClickHouse 账号？`, '确认初始化'))) return
  busy.value.add(key)
  try {
    await post(`/app-users/${row.userId}/ck-bindings/initialize`, {})
    ElMessage.success('CK 账号初始化已提交')
    await load()
  } catch { /* global error */ }
  finally { busy.value.delete(key) }
}
/** 只重试指定绑定，并同步刷新该用户最新的绑定摘要。 */
async function retryBinding(row: AppUserRow, b: CkBinding) {
  if (busy.value.has(b.bindingId)) return
  if (!(await confirmAction('重试 CK 账号绑定', `确认重试用户「${row.username}」的 ClickHouse 账号绑定？`, '确认重试'))) return
  busy.value.add(b.bindingId)
  try {
    await post(`/app-users/${row.userId}/ck-bindings/${b.bindingId}/retry`, {})
    ElMessage.success('已重试，请查看绑定状态')
    await load()
  } catch { /* global error */ }
  finally { busy.value.delete(b.bindingId) }
}
onMounted(load)
</script>
<style scoped>
.users-page { padding: 20px; }
.users-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
h2 { margin: 0; } p { color: var(--el-text-color-secondary); }
.el-tag + .el-tag { margin-left: 8px; }
.ck-binding { display: flex; align-items: center; gap: 8px; margin: 2px 0; }
.ck-err { color: var(--el-color-danger); cursor: help; }
.mono { font-family: var(--el-font-family-mono, monospace); }
</style>
