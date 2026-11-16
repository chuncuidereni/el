/**
 * index.ts
 *
 * 职责：Vue Router 配置与全局守卫：声明懒加载页面、公开登录路由、角色白名单和通配 404。
 * 边界：守卫会先恢复服务端会话；强制改密用户只能进入账号页，普通用户越权时回首页，通配路由必须保持在最后。
 */

import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 首次访问先经过独立登录页。
    { path: '/', redirect: '/login' },
    // 登录页:公开,不套侧栏(App.vue 按路由决定是否渲染框架)
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    // 所有页面都用 () => import() 懒加载:每个视图单独打 chunk,首屏只下首页那一份
    { path: '/home', name: 'home', component: () => import('@/views/HomeView.vue') },
    { path: '/account', name: 'account', component: () => import('@/views/AccountView.vue') },
    { path: '/app-users', name: 'app-users', component: () => import('@/views/AppUsersView.vue') },
    // 共享能力已合并进原表列表:旧地址重定向,不再保留独立菜单入口。
    { path: '/shared-data', redirect: '/raw' },
    { path: '/connections', name: 'connections', component: () => import('@/views/ConnectionsView.vue') },
    { path: '/system', name: 'system', component: () => import('@/views/SystemView.vue') },
    { path: '/dict', name: 'dict', component: () => import('@/views/DictView.vue') },
    { path: '/datasources', name: 'datasources', component: () => import('@/views/DataSourcesView.vue') },
    { path: '/sync-tasks', name: 'sync-tasks', component: () => import('@/views/SyncTasksView.vue') },
    // 工作流 = 多表关联 → 全量重建一张宽表,落到 dwd_data(设计见 docs/工作流-设计.md)
    // 工作台 → 工作流:多源仲裁 → 聚合层(每源一个物化视图)。
    // 老的 JOIN 型宽表页面已归档，旧书签跳转至现行工作流入口。
    { path: '/workflows', name: 'workflows', component: () => import('@/views/ArbWorkflowsView.vue') },
    { path: '/wide-tables', redirect: '/workflows' },
    { path: '/workspace', name: 'workspace', component: () => import('@/views/WorkspaceView.vue') },
    // props 让视图不必自己读 route 就能拿到标题/主色
    { path: '/raw', name: 'raw-ods', component: () => import('@/views/OdsLayerView.vue'), props: { subtitle: '贴源层 · 标准源表（ods_data）', accent: '#f59e0b' } },
    { path: '/dwd', name: 'dwd-warehouse', component: () => import('@/views/DwdLayerView.vue') },
    { path: '/sql-query', name: 'sql-query', component: () => import('@/views/SqlQueryView.vue') },
    // 工具:哈希计算(明文 → MD5 / SHA256 / SM3,见 internal/hashtool)
    { path: '/tools/hash', name: 'hash-tool', component: () => import('@/views/HashToolView.vue') },
    { path: '/tools/ddl-check', name: 'ddl-check', component: () => import('@/views/DdlCheckView.vue') },
    { path: '/tools/crypto', name: 'crypto-tool', component: () => import('@/views/CryptoToolView.vue') },
    // 通配必须放最后:vue-router 按声明顺序匹配,提前会吃掉后面所有路由
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
  ],
})

/**
 * 全局守卫:登录门槛 + 角色白名单。
 *  - 公开页(/login)直接放行;
 *  - 无凭据 → 跳登录页(带 redirect 回跳);
 *  - 已登录但角色未知(刷新后)→ 用 /me 恢复;仍拿不到则回登录页;
 *  - user 访问非白名单路由 → 兜回 /home。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.restored) await auth.restore()
  if (to.meta?.public) {
    if (to.path === '/login' && auth.isAuthed) {
      return { path: auth.mustChangePassword ? '/account' : '/home' }
    }
    return true
  }
  if (!auth.isAuthed) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (auth.mustChangePassword && to.path !== '/account') return { path: '/account' }
  if (!auth.canAccess(to.path)) return { path: '/home' }
  return true
})

export default router
