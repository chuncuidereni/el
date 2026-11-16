<!--
  LoginView.vue

  职责：登录页面：采集凭据、提交认证并处理登录错误与跳转。
  边界：读取登录 redirect 并提交凭据到 auth store；成功后只接受站内路径回跳，错误留在表单内展示。
-->

<template>
  <div class="login-page">
    <header class="login-header" aria-label="数仓平台">
      <span class="header-icon"><el-icon><Files /></el-icon></span>
      <span>数仓平台</span>
    </header>

    <main class="login-main">
      <section class="login-intro" aria-label="平台介绍">
        <p class="intro-eyebrow">DATA PLATFORM</p>
        <h1>让数据流转<br /><span>更清晰、更高效</span></h1>
        <p class="intro-subtitle">连接数据源、配置同步任务、探索数据并构建工作流，统一在一个工作台完成。</p>
        <div class="feature-grid">
          <div v-for="feature in features" :key="feature.title" class="feature-card">
            <el-icon class="feature-icon"><component :is="feature.icon" /></el-icon>
            <h2>{{ feature.title }}</h2>
            <p>{{ feature.description }}</p>
          </div>
        </div>
      </section>

      <section class="login-card" aria-labelledby="login-title">
        <div class="login-card-content">
          <p class="card-eyebrow">欢迎回来</p>
          <h2 id="login-title">登录数仓平台</h2>
          <p class="login-sub">请输入平台账号，登录后进入工作台</p>
          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" autofocus clearable @keyup.enter="onSubmit" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                placeholder="请输入密码"
                autocomplete="current-password"
                @keyup.enter="onSubmit"
              />
            </el-form-item>
            <el-button type="primary" native-type="submit" class="login-btn" :loading="loading">登录</el-button>
          </el-form>
        </div>
      </section>
    </main>

    <footer class="login-footer">数仓平台 · 数据同步与治理</footer>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Files, RefreshRight, Search, Share } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const features = [
  { icon: Files, title: '连接多种数据源', description: '统一管理文件、数据库与远程数据源，随时查看连接和数据状态。' },
  { icon: RefreshRight, title: '配置映射同步', description: '设置字段映射与运行计划，跟踪每次同步任务的执行结果。' },
  { icon: Search, title: '探索与分析数据', description: '在工作台查询库表、编写 SQL，快速验证数据和业务口径。' },
  { icon: Share, title: '编排数据工作流', description: '把多源处理流程组织起来，清楚掌握从源表到目标表的链路。' },
]

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 校验表单并提交登录；仅允许站内单斜杠回跳，避免开放重定向。 */
async function onSubmit() {
  if (!formRef.value || loading.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    loading.value = true
    try {
      await auth.login(form.username, form.password)
      // 优先回跳来源页;user 若来源不在其白名单,守卫会再兜到 /home
      const requested = typeof route.query.redirect === 'string' ? route.query.redirect : ''
      // 只接受应用内单斜杠路径，避免把登录跳转导向外部站点或登录页自身。
      const redirect = requested.startsWith('/') && !requested.startsWith('//')
        && !requested.startsWith('/login') ? requested : '/home'
      await router.replace(redirect)
    } catch (e: any) {
      // 状态码优先取拦截器直接挂在 Error 上的 status;兼容仍带 response 的情形
      const status = e?.status ?? e?.response?.status
      ElMessage.error(status === 401 ? '用户名或密码错误' : '登录失败:' + (e?.message ?? '未知错误'))
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  min-height: 100dvh;
  box-sizing: border-box;
  display: grid;
  grid-template-rows: auto 1fr auto;
  background: linear-gradient(125deg, #f1f7ff 0%, #f0f2ff 56%, #f5f3ff 100%);
  color: #17243b;
}
.login-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px clamp(24px, 4vw, 60px);
  font-size: 23px;
  font-weight: 750;
  letter-spacing: .08em;
  color: #122444;
}
.header-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 35px;
  height: 35px;
  border-radius: 11px;
  background: #1461e8;
  color: #fff;
  font-size: 21px;
}
.login-main {
  width: min(1100px, calc(100% - 48px));
  margin: auto;
  display: grid;
  grid-template-columns: minmax(0, 1.14fr) minmax(390px, .86fr);
  align-items: center;
  gap: clamp(32px, 5vw, 70px);
  padding: 24px 0 48px;
}
.intro-eyebrow, .card-eyebrow {
  margin: 0 0 14px;
  color: #1461e8;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .16em;
}
.login-intro h1 {
  margin: 0;
  font-size: clamp(34px, 3.8vw, 51px);
  line-height: 1.2;
  letter-spacing: -.02em;
  font-weight: 800;
}
.login-intro h1 span { color: #1461e8; }
.intro-subtitle {
  max-width: 510px;
  margin: 18px 0 32px;
  color: #53647e;
  font-size: 16px;
  line-height: 1.7;
}
.feature-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.feature-card {
  min-height: 154px;
  box-sizing: border-box;
  padding: 22px 22px 18px;
  border: 1px solid rgba(255,255,255,.96);
  border-radius: 16px;
  background: rgba(255,255,255,.74);
  box-shadow: 0 12px 26px rgba(44,73,139,.06);
}
.feature-icon { color: #1461e8; font-size: 24px; }
.feature-card h2 { margin: 13px 0 7px; font-size: 16px; line-height: 1.3; }
.feature-card p { margin: 0; color: #586983; font-size: 13px; line-height: 1.6; }
.login-card {
  min-height: 510px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  padding: clamp(30px, 4vw, 48px);
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 24px 48px rgba(30,50,101,.14);
}
.login-card-content { width: 100%; }
.login-card h2 { margin: 0; font-size: 29px; line-height: 1.3; }
.login-sub { margin: 10px 0 30px; color: #66758e; font-size: 14px; }
.login-card :deep(.el-form-item) { margin-bottom: 22px; }
.login-card :deep(.el-form-item__label) { color: #263550; font-weight: 600; }
.login-card :deep(.el-input__wrapper) { min-height: 40px; }
.login-card :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px #1461e8 inset; }
.login-card :deep(.el-button--primary) { background: #0c56ce; border-color: #0c56ce; }
.login-card :deep(.el-button--primary:hover) { background: #1461e8; border-color: #1461e8; }
.login-btn { width: 100%; height: 42px; margin-top: 8px; font-weight: 600; }
.login-footer {
  padding: 20px 24px 25px;
  text-align: center;
  color: #8795ae;
  font-size: 12px;
}
@media (max-width: 900px) {
  .login-main { grid-template-columns: 1fr; max-width: 570px; gap: 28px; padding-top: 28px; }
  .login-intro h1 br { display: none; }
  .login-card { min-height: 0; }
}
@media (max-width: 520px) {
  .login-header { padding: 18px 20px; font-size: 19px; }
  .login-main { width: calc(100% - 32px); padding: 15px 0 28px; }
  .login-intro h1 { font-size: 31px; }
  .intro-subtitle { margin: 12px 0 20px; font-size: 14px; }
  .feature-grid { gap: 9px; }
  .feature-card { min-height: 0; padding: 16px; }
  .feature-card h2 { font-size: 14px; }
  .feature-card p { font-size: 12px; }
  .login-card { padding: 32px 24px; }
  .login-card h2 { font-size: 25px; }
}
</style>
