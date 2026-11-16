<!--
  AccountView.vue

  职责：当前账号页面：展示身份信息、权限能力和账号相关操作。
  边界：从 auth store 展示当前账号和权限能力；账号相关入口交给路由或父布局，本页不直接修改服务端凭据。
-->

<template>
  <div class="account-page">
    <h2>账号安全</h2>
    <p>当前账号：{{ auth.username }}</p>
    <el-alert v-if="auth.mustChangePassword" title="请先修改初始密码，再使用平台功能。" type="warning" :closable="false" />
    <el-form label-width="110px" @submit.prevent="changePassword">
      <el-form-item label="原密码"><el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" placeholder="请输入新密码" /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
      <el-form-item><el-button type="primary" native-type="submit" :loading="saving">修改密码并重新登录</el-button></el-form-item>
    </el-form>
    <el-button v-if="!auth.mustChangePassword" :loading="saving" @click="logoutAll">退出所有设备</el-button>
  </div>
</template>
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authApi } from '@/api'
import { confirmAction } from '@/utils/confirm'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
const router = useRouter()
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
/** 校验新旧密码、UTF-8 字节上限和二次确认；成功后清空会话并强制重新登录。 */
async function changePassword() {
  if (saving.value) return
  if (!form.oldPassword || !form.newPassword || new TextEncoder().encode(form.newPassword).length > 72) {
    ElMessage.warning('请输入原密码和新密码；新密码 UTF-8 编码不超过 72 字节'); return
  }
  if (form.newPassword !== form.confirm) { ElMessage.warning('两次新密码不一致'); return }
  if (!(await confirmAction('修改密码', '修改后需要用新密码重新登录，确认修改？', '确认修改'))) return
  saving.value = true
  try {
    await authApi.changePassword(form.oldPassword, form.newPassword)
    auth.clear()
    ElMessage.success('密码已修改，请重新登录')
    await router.replace('/login')
  } catch { /* HTTP interceptor presents errors */ }
  finally { saving.value = false }
}
/** 二次确认后注销全部设备；失败保留当前会话并交给统一错误提示。 */
async function logoutAll() {
  try { await ElMessageBox.confirm('退出所有设备上的登录会话？', '退出所有设备') } catch { return }
  saving.value = true
  try { await authApi.logoutAll(); auth.clear(); await router.replace('/login') }
  catch { /* HTTP interceptor presents errors */ }
  finally { saving.value = false }
}
</script>
<style scoped>
.account-page { max-width: 620px; padding: 24px; }
.el-form { margin-top: 24px; }
</style>
