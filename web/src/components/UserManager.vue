<!--
  UserManager.vue

  职责：用户管理主组件：组合用户列表、搜索、详情和编辑流程。
  边界：组合用户搜索、列表、详情和新增编辑弹窗；CRUD 成功后刷新当前查询，并分别呈现加载、空数据和失败状态。
-->

<template>
  <div class="user-manager">
    <div class="head">
      <div class="title">用户管理</div>
      <div class="actions">
        <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
        <el-button size="small" type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
    </div>

    <el-table :data="users" v-loading="loading" border size="small">
      <el-table-column prop="name" label="用户名" min-width="140">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <!-- 只读标记只是提示:真正的拦截在操作列与各方法里各做一次,防止绕过按钮直达逻辑 -->
          <el-tag v-if="isReadOnly(row.name)" size="small" type="info" effect="plain" class="readonly-tag">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="authType" label="认证方式" min-width="140" />
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="主机限制" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="h in row.hosts" :key="h" size="small" class="host-tag">{{ h }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <!-- 三个按钮统一挂 op-act:只放大点击区并加深文字色(类定义见 assets/theme.css,全局共用) -->
          <el-button size="small" class="op-act" @click="openView(row)">查看</el-button>
          <!-- 编辑/删除对连接登录用户禁用:它就是当前连接正在使用的账号,改密码或删号会直接把连接弄失效 -->
          <el-button size="small" class="op-act" :disabled="isReadOnly(row.name)" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" class="op-act" :disabled="isReadOnly(row.name)" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 查看权限 -->
    <el-dialog v-model="viewVisible" :title="`用户权限 · ${viewUser?.name ?? ''}`" width="640px" :close-on-click-modal="false">
      <el-descriptions v-if="viewUser" :column="1" border size="small" class="view-desc">
        <el-descriptions-item label="用户名">{{ viewUser.name }}</el-descriptions-item>
        <el-descriptions-item label="认证方式">{{ viewUser.authType }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ viewUser.remark || '—' }}</el-descriptions-item>
        <el-descriptions-item label="主机限制">
          <el-tag v-for="h in viewUser.hosts" :key="h" size="small" class="host-tag">{{ h }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <div class="view-title">库表权限</div>
      <el-table :data="viewGrants" size="small" border v-loading="viewLoading">
        <el-table-column prop="database" label="数据库" min-width="140" />
        <el-table-column prop="table" label="表" min-width="140" />
        <el-table-column label="权限" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="accessTagType(row.access)">{{ accessLabel(row.access) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="viewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 新建/编辑用户 -->
    <UserEditDialog v-model="editDialogVisible" :conn-id="connId" :user="editing" @saved="onSaved" />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { usersApi } from '@/api'
import { confirmDestructive } from '@/utils/confirm'
import type { GrantRule, UserInfo } from '@/types'
import UserEditDialog from '@/components/UserEditDialog.vue'

const props = defineProps<{
  connId: number
  /** 只读用户(连接的登录用户,如 default):只能查看,不可编辑/删除 */
  readOnlyUser?: string
  /** 侧边栏「+ 新建用户」触发:值变化时自动打开新建弹窗 */
  createUserFlag?: number
}>()

const emit = defineEmits<{ (e: 'changed'): void }>()

// 侧边栏「+ 新建用户」按钮触发:值变化即打开新建弹窗
// immediate 不可省:父级可能在挂载本组件之前就已把 flag 置为非 0,漏掉初值这次触发就点不开弹窗
watch(
  () => props.createUserFlag,
  (v) => {
    if (v) openCreate()
  },
  { immediate: true },
)

const loading = ref(false)
const users = ref<UserInfo[]>([])
const editDialogVisible = ref(false)
const editing = ref<UserInfo | null>(null)
const viewVisible = ref(false)
const viewUser = ref<UserInfo | null>(null)
const viewGrants = ref<GrantRule[]>([])
const viewLoading = ref(false)

async function load() {
  // 连接未就绪时 connId 为 -1,发请求只会拿到无意义的错误;等 watch(connId) 到位后再拉
  if (props.connId <= 0) return // 连接尚未就绪
  loading.value = true
  try {
    // 后端在账号无 system.users 查询权限时返回空列表而非报错,所以空数组是正常结果,不要当成加载失败处理
    users.value = await usersApi.list(props.connId)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function onSaved() {
  load()
  emit('changed')
}

function openCreate() {
  editing.value = null
  // 先关再开,确保弹窗每次都能正常弹出
  // 同步置 false 后等 nextTick 置 true:同一个 tick 内 false→true 会被 Vue 合并成"没变过",动画与内部初始化都不会重跑
  editDialogVisible.value = false
  nextTick(() => {
    editDialogVisible.value = true
  })
}

// 除了 createUserFlag 之外的第二条打开入口:父级拿到 ref 后可直接调用
defineExpose({ openCreate })

function isReadOnly(name: string): boolean {
  return !!props.readOnlyUser && name === props.readOnlyUser
}

async function openView(u: UserInfo) {
  viewUser.value = u
  // 先清空再拉:沿用上一个人的授权会让弹窗在请求返回前短暂显示错误数据
  viewGrants.value = []
  viewVisible.value = true
  viewLoading.value = true
  try {
    viewGrants.value = await usersApi.grants(props.connId, u.name)
  } catch {
    /* 拦截器已提示 */
  } finally {
    viewLoading.value = false
  }
}

function accessLabel(a: string): string {
  // 认识的值翻译成中文,不认识的原样透出,便于后端扩展权限级别时前端先能用
  switch (a) {
    case 'select':
      return '查询'
    case 'readwrite':
      return '读写'
    case 'all':
      return '全部'
    default:
      return a
  }
}

function accessTagType(a: string): 'success' | 'warning' | 'danger' | 'info' {
  // 用颜色编码权限量级:绿=只读,红=全部权限;未知级别归到最中性的 info
  switch (a) {
    case 'select':
      return 'success'
    case 'readwrite':
      return 'warning'
    case 'all':
      return 'danger'
    default:
      return 'info'
  }
}

function openEdit(u: UserInfo) {
  // 按钮已 disabled 仍再判一次:disabled 只是 UI 层的约束,逻辑入口不该依赖它(例如后续被其它入口调用)
  if (isReadOnly(u.name)) {
    ElMessage.warning('默认用户只读,不可编辑')
    return
  }
  editing.value = u
  editDialogVisible.value = true
}

async function remove(u: UserInfo) {
  // 同 openEdit:UI 禁用之外再兜一层,避免登录用户把自己的连接凭据删掉
  if (isReadOnly(u.name)) {
    ElMessage.warning('默认用户只读,不可删除')
    return
  }
  // 需手动输入用户名确认;输入不匹配时弹窗不会关闭
  if (!(await confirmDestructive('删除用户', u.name, '删除'))) return
  try {
    await usersApi.remove(props.connId, u.name)
    ElMessage.success('已删除')
    load()
    emit('changed')
  } catch {
    /* 拦截器已提示 */
  }
}

// 连接就绪(之前为 -1)后自动加载用户列表
watch(
  () => props.connId,
  (v) => {
    if (v > 0) load()
  },
)

onMounted(load)
</script>

<style scoped>
.user-manager {
  height: 100%;
  overflow: auto;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.title {
  font-size: 15px;
  font-weight: 600;
}
.host-tag {
  margin: 2px 4px 2px 0;
  font-family: var(--font-mono);
  font-size: 12px;
}
.readonly-tag {
  margin-left: 6px;
  font-size: 11px;
}
.view-desc {
  margin-bottom: 12px;
}
.view-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
</style>
