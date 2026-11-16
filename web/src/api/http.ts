/**
 * http.ts
 *
 * 职责：HTTP 基础适配层：统一响应信封、会话/CSRF、静默请求、错误提示和 Blob 导出超时。
 * 边界：所有 API 请求都经 `/api` axios 实例；401 会清凭据并跳登录，silent 只抑制通用消息，轮询必须自行防止旧响应覆盖。
 */

import axios from 'axios'
import { ElMessage } from 'element-plus'
import { setStorageUser } from '@/utils/privateStorage'

// silent 只抑制通用消息；认证失效仍统一退出。登录请求使用 _skipAuth 避免重复导航。
declare module 'axios' {
  export interface AxiosRequestConfig {
    silent?: boolean
    /** 内部标记:401 时不要重复弹登录框(避免无限循环) */
    _skipAuth?: boolean
  }
}

/**
 * 后端统一 JSON 信封。code === 0 表示业务成功，非 0 由响应拦截器拒绝；
 * message 供错误展示，data 才是各 API 包装器最终返回的业务数据。文件流等非信封响应不套用此类型。
 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

const CRED_KEY = 'el2ck.session'

/** CSRF token is kept in memory; authentication uses the HttpOnly session cookie. */
let csrf: { token: string; headerName: string } | undefined
let csrfRequest: Promise<void> | undefined
export function resetCsrf(): void { csrf = undefined; csrfRequest = undefined }

/** 仅记录页面会话标记，不保存密码或认证凭据。 */
export function markSession(): void {
  sessionStorage.removeItem('el2ck.creds')
  sessionStorage.setItem(CRED_KEY, '1')
}

/** 清除凭据(登出)。 */
export function clearCredentials(): void {
  setStorageUser(0)
  sessionStorage.removeItem('el2ck.creds')
  sessionStorage.removeItem(CRED_KEY)
  resetCsrf()
}

/** 会话里是否已有凭据(路由守卫据此判断「是否已登录」)。 */
export function hasCredentials(): boolean {
  return !!sessionStorage.getItem(CRED_KEY)
}

export const http = axios.create({ baseURL: '/api', timeout: 120_000 })

// 写请求自动携带当前服务端会话的 CSRF token。
http.interceptors.request.use(
  async (config) => {
    if (!['get', 'head', 'options'].includes(config.method?.toLowerCase() || 'get')) {
      if (!csrf) {
        if (!csrfRequest) csrfRequest = http.get('/csrf', { silent: true }).then((r) => { csrf = r.data.data }).finally(() => { csrfRequest = undefined })
        await csrfRequest
      }
      if (csrf) config.headers[csrf.headerName] = csrf.token
    }
    return config
  },
  (err) => Promise.reject(err),
)

// 响应拦截器识别业务信封并处理 HTTP 错误；silent 不吞异常，也不绕过认证失效处理。
http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse
    // 只有带 code 字段的才是本项目的 JSON 信封;文件流等直接放行
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        if (!(resp.config as any)?.silent) ElMessage.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message))
      }
    }
    return resp
  },
  (err) => {
    const silent = err.config?.silent
    const status = err.response?.status

/**
 * 普通 401：清理本地会话并按当前路径跳登录页；已在登录页时不重复跳转。
 * _skipAuth 仅供登录接口自身使用；silent 轮询仍会清凭据并跳转，避免后台永远查询失效会话。
 * 这一分支直接拒绝原始 Axios 错误，status 位于 error.response.status，不走下方 wrapped Error 形态。
 */
    // 401 未授权:凭据失效/未登录。清掉本地凭据并跳登录页(取代旧的弹框事件)。
    // 只有显式 _skipAuth 请求不劫持导航。
    if (status === 401 && !err.config?._skipAuth) {
      clearCredentials()
      if (typeof window !== 'undefined' && window.location && !window.location.pathname.startsWith('/login')) {
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.assign('/login?redirect=' + redirect)
      }
      return Promise.reject(err)
    }

    const msg = err.response?.data?.message || err.message || '网络错误'
    if (!silent) ElMessage.error(msg)
/**
 * 非 401 或豁免了自动导航的错误统一包装。silent 仅抑制 ElMessage，不改变 reject；
 * 包装后的 Error 保留服务端 message 和 status，便于轮询区分 404、登录页区分密码错误等。
 */
    // 是否 silent 都抛「带服务端消息」的 Error。
    // 原先 silent 时直接抛 axios 原始错误,调用方只能拿到 “Request failed with status code 502”,
    // 后端那句真正的失败原因(如「读取服务运行指标失败: … Not enough privileges …」)被丢掉 ——
    // 而 silent 的调用方(轮询、监控页)恰恰是自己提示错误、最需要真实原因的那一类。
    // 原始 HTTP 状态挂在 status 上返回:登录页靠它区分密码错误与网络故障。
    const wrapped = new Error(msg) as Error & { status?: number }
    wrapped.status = status
    return Promise.reject(wrapped)
  },
)

// 下面几个包装器统一把 {code,message,data} 信封拆掉,只把 data 交给调用方:
// code!=0 的情况已被响应拦截器 reject,能走到 then 的都是成功响应。
/**
 * 通用包装器拆掉响应信封并返回 data。非 0 code 与 HTTP 错误已在拦截器拒绝，
 * 因而 then 分支只处理成功业务响应；silent 仍透传给请求配置。
 */
export function get<T>(url: string, params?: Record<string, unknown>, silent = false): Promise<T> {
  return http.get(url, { params, silent }).then((r) => (r.data as ApiResponse<T>).data)
}

export function post<T = unknown>(url: string, data?: unknown): Promise<T> {
  return http.post(url, data).then((r) => (r.data as ApiResponse<T>).data)
}

export function put<T = unknown>(url: string, data?: unknown): Promise<T> {
  return http.put(url, data).then((r) => (r.data as ApiResponse<T>).data)
}

export function del<T = unknown>(url: string, silent = false): Promise<T> {
  return http.delete(url, { silent }).then((r) => (r.data as ApiResponse<T>).data)
}

/** 导出专用超时:与后端 exportTimeout(30 分钟)对齐。
 *  ⚠️ 不能沿用实例上的 120s —— 那会把后端为「大结果集导出」放宽的窗口直接作废,
 *     大导出会在 120s 被前端打断,而后端还在慢慢 ETL。 */
export const EXPORT_TIMEOUT_MS = 30 * 60 * 1000

/**
 * POST 并接收二进制响应(用于【流式 CSV 导出】)。
 *
 * 服务端成功时直接回 `text/csv`(边查边写,不在内存里攒整份);
 * 失败时仍然回 JSON 错误体 —— axios 会把它包成 Blob,这里负责解回来,
 * 否则用户只会看到「Request failed with status code 400」。
 *
 * silent=true 时由调用方自行提示(避免和拦截器的默认提示重复)。
 */
export async function postBlob(url: string, data?: unknown, silent = false, timeoutMs?: number): Promise<Blob> {
  try {
    // 只在显式传入时才覆盖 timeout:留空则沿用实例默认值,避免 0/undefined 把超时清成「永不超时」
    const resp = await http.post(url, data, {
      responseType: 'blob',
      silent,
      ...(timeoutMs ? { timeout: timeoutMs } : {}),
    })
    return resp.data as Blob
  } catch (e: any) {
    let msg = e?.message || '导出失败'
    // responseType=blob 时,连服务端的 JSON 错误体也会被包成 Blob,
    // 必须读文本再 parse 一次才能拿到后端真正的失败原因。
    const body = e?.response?.data
    if (body instanceof Blob) {
      try {
        const j = JSON.parse(await body.text()) as ApiResponse
        if (j?.message) msg = j.message
      } catch {
        /* 不是 JSON,保留原信息 */
      }
    }
    if (!silent) ElMessage.error(msg)
    throw new Error(msg)
  }
}
