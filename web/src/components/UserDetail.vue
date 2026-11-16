<!--
  UserDetail.vue

  职责：用户详情组件：展示账号身份、角色和状态信息。
  边界：只读呈现 UserInfo 的身份、角色、状态和权限摘要；资料修改由父级编辑弹窗完成。
-->

<template>
  <!--
    用户页签的正文:「用户信息 + 库表权限」只读展示,改 / 删走子组件与确认弹窗。
    由 WorkspaceView 以 lazy 页签承载,一个页签一个实例;切换连接时父级会清空页签列表,
    所以不需要 watch props —— 挂载时拉一次即可。
    唯一的例外是编辑保存后要重拉(见 onSaved),否则显示的还是改之前的值。
  -->
  <div class="user-detail">
    <div class="head">
      <span class="title">用户 · {{ userName }}</span>
      <!-- 「默认」标记 = 该用户就是当前连接的登录账号,是只读的判定依据之一,不是普通备注 -->
      <el-tag v-if="isReadOnly" size="small" type="info" effect="plain" class="ro-tag">默认</el-tag>
      <div class="actions">
        <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
        <!-- 两个按钮禁用同一个原因:目标是连接配置里的登录账号本身,改密码或删号等于动当前连接正在用的凭据 -->
        <el-button size="small" type="primary" :disabled="isReadOnly" @click="editVisible = true">编辑</el-button>
        <el-button size="small" type="danger" plain :disabled="isReadOnly" @click="onDelete">删除</el-button>
      </div>
    </div>

    <!-- 逐项 ?? '—' 兜底:user 为 null(接口没查到 / 无 system.users 权限)时不能出现空白行 -->
    <el-descriptions :column="1" border size="small" class="info" v-loading="loading">
      <el-descriptions-item label="用户名">{{ user?.name ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="认证方式">{{ user?.authType ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="备注">{{ user?.remark || '—' }}</el-descriptions-item>
      <el-descriptions-item label="主机限制">
        <!-- hosts 可能为空数组(等价 ANY),也兼容后端不返回该字段的情况,统一落到占位符 -->
        <template v-if="user?.hosts?.length">
          <el-tag v-for="h in user.hosts" :key="h" size="small" class="host-tag">{{ h }}</el-tag>
        </template>
        <span v-else class="muted">—</span>
      </el-descriptions-item>
    </el-descriptions>

    <div class="section-title">库表权限</div>
    <!-- 授权与用户信息是两次请求:这里为空只说明"没有授权记录"(或授权接口失败),不代表用户不存在 -->
    <el-table :data="grants" size="small" border>
      <el-table-column prop="database" label="数据库" min-width="140" />
      <el-table-column prop="table" label="表" min-width="140" />
      <el-table-column label="权限" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="accessTagType(row.access)">{{ accessLabel(row.access) }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <UserEditDialog v-model="editVisible" :conn-id="connId" :user="user" @saved="onSaved" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { usersApi } from '@/api'
import { confirmDestructive } from '@/utils/confirm'
import type { GrantRule, UserInfo } from '@/types'
import UserEditDialog from '@/components/UserEditDialog.vue'

/** userName 由父级从选中的树节点带下来;readOnlyUser 是当前连接的登录账号,用于判定能否改删 */
const props = defineProps<{ connId: number; userName: string; readOnlyUser?: string }>()
// deleted 让父级收起本页签;changed 让用户列表/左树重新拉一次 —— 两个事件的消费方不同,不能合并
const emit = defineEmits<{
  (e: 'deleted'): void
  (e: 'changed'): void
}>()

const user = ref<UserInfo | null>(null)
// 授权列表与 user 分开存:两者来自不同接口,一个失败不该让另一个也变空
const grants = ref<GrantRule[]>([])
const loading = ref(false)
const editVisible = ref(false)

// 只读判定只认"连接登录用户"这一个来源:默认用户是连接的凭据本身,不允许改名/删号
const isReadOnly = computed(() => !!props.readOnlyUser && props.userName === props.readOnlyUser)

async function load() {
  loading.value = true
  try {
    // 没有"查单个用户"的接口,只能拉全量再按名字筛;筛不到置 null,由模板的 ?? '—' 兜底
    // 注意后端在账号无 system.users 权限时会返回空列表(不算错误),所以"全是 —"也可能是权限不足而非用户不存在
    const users = await usersApi.list(props.connId)
    user.value = users.find((u) => u.name === props.userName) ?? null
    // 授权单独一次请求:前者失败不该连带把用户信息也清空
    grants.value = await usersApi.grants(props.connId, props.userName)
  } catch {
    /* 拦截器已提示;不清空 user/grants —— 刷新失败时保留上一份数据,比瞬间变成一屏「—」更好判断 */
  } finally {
    // loading 收在 finally:失败路径同样要解锁,否则刷新按钮一直转
    loading.value = false
  }
}

function onSaved() {
  // 编辑弹窗只负责提交,保存后由本组件重新拉一次,保证展示的是服务端实际生效的结果
  load()
  emit('changed')
}

async function onDelete() {
  // 要求手动输入用户名确认,删号不可恢复
  if (!(await confirmDestructive('删除用户', props.userName, '删除'))) return
  try {
    await usersApi.remove(props.connId, props.userName)
    ElMessage.success('已删除')
    // deleted 让父级收起本面板,changed 让用户列表/树同步刷新,两者职责不同不可合并
    emit('deleted')
    emit('changed')
  } catch {
    /* 拦截器已提示 */
  }
}

function accessLabel(a: string): string {
  // default 分支原样返回:后端将来新增权限级别时,列表显示原始值而不是空白
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
  // 颜色替用户做风险分级:查询安全(绿) → 读写(黄) → 全部(红),越权越大越醒目
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

// 挂载即加载:页签是 lazy 的,首次激活时父级才创建本实例,等价于"打开时拉一次"
onMounted(load)
</script>

<style scoped>
/* 撑满页签容器并自行滚动,表多时不让整页(含页签条)被顶出去 */
.user-detail {
  height: 100%;
  overflow: auto;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.title {
  font-size: 15px;
  font-weight: 600;
}
.ro-tag {
  font-size: 11px;
}
.actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
}
.info {
  margin-bottom: 14px;
}
.section-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
/* 等宽字体:主机限制常见 IP / 网段,逐字符核对时等宽更好认 */
.host-tag {
  margin: 2px 4px 2px 0;
  font-family: var(--font-mono);
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
