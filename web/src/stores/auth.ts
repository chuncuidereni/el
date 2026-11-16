/**
 * auth.ts
 *
 * 职责：认证 Pinia 仓库：维护用户、角色、改密状态和 SQL 执行能力，并处理登录、恢复、登出与路径权限。
 * 边界：刷新后必须通过 /me 重新确认身份，不能只信任缓存角色；普通用户仅访问 USER_ALLOWED_PATHS，管理员权限仍需后端 403 兜底。
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AppRole, AuthMe } from '@/types'
import { authApi } from '@/api'
import { clearCredentials, markSession, resetCsrf } from '@/api/http'
import { setStorageUser } from '@/utils/privateStorage'

const USER_KEY = 'el2ck.user'
const ROLE_KEY = 'el2ck.role'

/**
 * 普通用户(role=user)可见的路由白名单 = 首页 + 「数据源」组 + 「工作台」组。
 * 管理员不受此限。前缀匹配:`/foo` 命中 `/foo` 与 `/foo/xxx`。
 * ⚠️ 这是前端可见性口径;后端另对「隐藏页独占的敏感接口」做 403 兜底。
 */
export const USER_ALLOWED_PATHS = [
  '/login',
  '/home',
  '/datasources',
  '/sync-tasks',
  '/raw',
  '/sql-query',
  '/workflows',
  '/dwd',
  '/account',
]

/** 服务端会话对应的页面状态；不在浏览器保存登录密码。 */
export const useAuthStore = defineStore('auth', () => {
  sessionStorage.removeItem('el2ck.creds')
  // 页面刷新后须通过 /me 重新确认凭据，不能仅相信本地缓存的角色。
  const username = ref('')
  const role = ref<AppRole | ''>('')
  const userId = ref(0)
  const mustChangePassword = ref(false)
  const sqlExecutionEnabled = ref(false)
  const restored = ref(false)

  const isAdmin = computed(() => role.value === 'admin')
  const isAuthed = computed(() => role.value === 'admin' || role.value === 'user')

  function apply(me: AuthMe) {
    userId.value = me.userId || 0
    setStorageUser(userId.value)
    sqlExecutionEnabled.value = me.capabilities?.sqlExecution === true
    mustChangePassword.value = !!me.mustChangePassword
    restored.value = true
    markSession()
    username.value = me.username
    role.value = me.role
    sessionStorage.setItem(USER_KEY, me.username)
    sessionStorage.setItem(ROLE_KEY, me.role)
  }

  function clear() {
    userId.value = 0
    setStorageUser(0)
    sqlExecutionEnabled.value = false
    mustChangePassword.value = false
    restored.value = true
    clearCredentials()
    username.value = ''
    role.value = ''
    sessionStorage.removeItem(USER_KEY)
    sessionStorage.removeItem(ROLE_KEY)
  }

  /** 登录:校验通过才落凭据与角色;失败清干净并抛出让登录页提示。 */
  async function login(user: string, pass: string): Promise<AuthMe> {
    resetCsrf()
    let me: AuthMe
    try {
      me = await authApi.login(user, pass)
    } catch (e) {
      clear()
      throw e
    }
    resetCsrf()
    apply(me)
    return me
  }

  /** 登出:清凭据 + 清角色(跳登录页由调用方做)。 */
  async function logout() {
    // Refresh the token first: an expired server session also invalidates its old CSRF token.
    resetCsrf()
    try {
      await authApi.logout()
    } catch (error) {
      // An expired session is already logged out. Other failures must remain visible.
      if ((error as { status?: number }).status !== 401) throw error
    }
    clear()
  }

  /** 刷新后恢复角色:有凭据就用 /me 校准;401/无凭据则清空。 */
  async function restore(): Promise<void> {
    try {
      apply(await authApi.me())
    } catch {
      clear()
    }
  }

  /** 该路径当前角色是否可访问(管理员全通;user 走白名单;未登录一律否)。 */
  function canAccess(path: string): boolean {
    if (isAdmin.value) return true
    if (role.value === 'user') {
      return USER_ALLOWED_PATHS.some((p) => path === p || path.startsWith(p + '/'))
    }
    return false
  }

  return { username, userId, role, mustChangePassword, sqlExecutionEnabled, restored, isAdmin, isAuthed, login, logout, clear, restore, canAccess }
})
