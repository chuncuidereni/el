<!--
  UserEditDialog.vue

  职责：用户编辑弹窗：维护账号资料、角色与可选密码。
  边界：由 modelValue 控制并回显用户字段；保存时校验角色和可选密码，成功后发出 saved，不直接改写父级列表。
-->

<template>
  <!-- 表单较长,误点遮罩会连输入一起丢掉,因此禁用点击遮罩关闭,只留取消/关闭按钮 -->
  <el-dialog
    :model-value="modelValue"
    :title="user ? '编辑用户' : '新建用户'"
    width="760px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-form label-width="90px">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="用户名">
            <!-- 编辑时后端用【原名】拼请求路径(update/remove 都把 name 放在 /users/{name}),允许改名会让路径与提交体不一致 -->
            <el-input v-model="form.name" :disabled="!!user" placeholder="字母/数字/下划线" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item :label="user ? '新密码' : '密码'">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="user ? '留空保持不变' : '用户密码'"
            />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="备注">
        <el-input v-model="form.remark" placeholder="可选,显示在用户节点上" maxlength="50" show-word-limit />
      </el-form-item>

      <el-form-item label="主机限制">
        <!-- 一行一条:用换行分隔比让用户自己拼逗号/引号更不容易写坏,提交前再拆成数组 -->
        <el-input
          v-model="hostsText"
          type="textarea"
          :rows="3"
          placeholder="每行一条,如:&#10;IP 127.0.0.1&#10;IP 192.168.0.0/24&#10;LIKE %.example.com&#10;ANY(任意主机)"
        />
        <div class="hint">IP/LIKE/NAME 前缀 + 值,支持 CIDR 与通配;空则 ANY</div>
      </el-form-item>

      <el-divider content-position="left">
        <span class="divider-title">库表权限</span>
      </el-divider>
      <div class="grant-tip">
        库/表支持 `*` 通配:如库 `default`、表 `t_*`(该库下 t_ 开头的表);权限分为 查询 / 读写 / 全部。
      </div>
      <el-table :data="grants" size="small" border class="grant-table">
        <el-table-column label="数据库" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.database" placeholder="*" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="表" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.table" placeholder="*" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="权限" width="120">
          <template #default="{ row }">
            <el-select v-model="row.access" size="small" style="width: 100%">
              <el-option label="查询" value="select" />
              <el-option label="读写" value="readwrite" />
              <el-option label="全部" value="all" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="52" align="center">
          <template #default="{ $index }">
            <!-- 直接原地 splice:grants 是 ref 数组,元素与表格行一一对应,无需先拷贝 -->
            <el-button link type="danger" :icon="Delete" @click="grants.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" :icon="Plus" class="add-grant" @click="addGrant">添加规则</el-button>
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import { usersApi } from '@/api'
import type { GrantRule, UserInfo } from '@/types'
import { confirmAction } from '@/utils/confirm'

const props = defineProps<{
  modelValue: boolean
  connId: number
  /** 为 null 表示新建;否则编辑该用户 */
  user: UserInfo | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const saving = ref(false)
const form = reactive({ name: '', password: '', remark: '' })
const hostsText = ref('')
const grants = ref<GrantRule[]>([{ database: 'default', table: '*', access: 'select' }])

function resetDialog() {
  // 每次打开都以 props.user 为基线重置:复用组件实例时残留上一次的输入会造成"改了别人的感觉"
  form.name = props.user?.name ?? ''
  // 密码从不回显(后端也只有摘要),留空即"不修改",由提交时按编辑/新建区分是否必填
  form.password = ''
  form.remark = props.user?.remark ?? ''
  // 数组 → 多行文本,与输入框的"每行一条"约定对应
  hostsText.value = props.user?.hosts.join('\n') ?? ''
  // 授权不在此处预置:新建时给一条最小的默认规则,编辑时下面异步拉真实数据覆盖
  grants.value = [{ database: 'default', table: '*', access: 'select' }]
}

watch(
  () => props.modelValue,
  async (v) => {
    // 只在打开时初始化:关闭动作不需要重置,否则会带着动画把表单内容闪一下
    if (!v) return
    resetDialog()
    if (props.user) {
      // 编辑:授权是独立接口,拉回来后覆盖上面的默认行;返回空说明确实没配过,给一条全通配规则当编辑起点
      try {
        const g = await usersApi.grants(props.connId, props.user.name)
        grants.value = g.length ? g : [{ database: '*', table: '*', access: 'select' }]
      } catch {
        /* 拦截器已提示 */
      }
    }
  },
)

function addGrant() {
  // 新规则默认给最小权限(查询),避免顺手点"添加规则"就得到一条高权限条目
  grants.value.push({ database: 'default', table: '*', access: 'select' })
}

async function onSave() {
  // 多行文本 → 数组:丢掉空行(用户顺手敲的回车),全空即 ANY
  const hosts = hostsText.value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  const payload = {
    name: form.name.trim(),
    // 不 trim:密码里的空格是有效字符
    password: form.password,
    remark: form.remark.trim(),
    hosts,
    grants: grants.value.map((g) => ({
      // 库/表留空视为通配 *:比让用户手输星号或报错更符合直觉
      database: (g.database || '*').trim(),
      table: (g.table || '*').trim(),
      access: g.access,
    })),
  }
  if (!payload.name) {
    ElMessage.warning('请输入用户名')
    return
  }
  // 密码只有新建时必填;编辑时留空表示沿用原密码
  if (!props.user && !payload.password) {
    ElMessage.warning('请输入密码')
    return
  }
  const grantTip = payload.grants.length ? `，同时更新 ${payload.grants.length} 条授权规则` : ''
  if (!(await confirmAction(props.user ? '保存库用户' : '新建库用户',
    props.user ? `确认保存对库用户「${props.user.name}」的修改${grantTip}？` : `确认新建库用户「${payload.name}」${grantTip}？`,
    props.user ? '确认保存' : '确认新建'))) return
  saving.value = true
  try {
    // 编辑走 update(以原名为键,避免用户名被表单改动后找不到目标),新建走 create
    if (props.user) {
      await usersApi.update(props.connId, props.user.name, payload)
      ElMessage.success('已更新')
    } else {
      await usersApi.create(props.connId, payload)
      ElMessage.success('已创建')
    }
    // 先通知父级刷新,再关闭弹窗:反过来会让父级在弹窗收起后才更新,列表短暂显示旧数据
    emit('saved')
    emit('update:modelValue', false)
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.hint,
.grant-tip {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 4px;
  line-height: 1.5;
}
.divider-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.grant-table {
  margin-top: 6px;
}
.add-grant {
  margin-top: 8px;
}
</style>
