import { clearWebUiToken, readWebUiToken } from '../utils/storage'
import { readJsonResponse, toJsonBody } from '../utils/json'
import { normalizeVisibleMessage } from '../utils/errorMessages'
import type { TokenStorage } from '../utils/storage'

/**
 * WebUI API 请求可以注入 fetch 和 storage，方便单元测试直接验证请求形状。
 */
export type WebUiFetchLike = (input: RequestInfo | URL, init?: RequestInit) => Promise<Pick<Response, 'ok' | 'status' | 'json'>>

/**
 * 所有 API 模块共享同一套 JSON 请求约定，避免每个页面重新拼装鉴权头。
 */
export type WebUiJsonRequestOptions = {
  method?: string
  body?: unknown
  headers?: HeadersInit
  authenticated?: boolean
  includeJson?: boolean
  storage?: TokenStorage
  fetchImpl?: WebUiFetchLike
  redirectToLogin?: () => void
}

/**
 * 所有受保护请求都统一附带 Accept 和可选的 Bearer token。
 */
export function buildAuthHeaders(includeJson = false, storage: TokenStorage = window.sessionStorage): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
  }
  const token = readWebUiToken(storage)
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  if (includeJson) {
    headers['Content-Type'] = 'application/json'
  }
  return headers
}

/**
 * 认证失效时同时清理本地 token，并把用户送回登录页。
 */
export function handleUnauthorized(
  storage: TokenStorage = window.sessionStorage,
  redirectToLogin: () => void = () => window.location.replace('/login'),
): void {
  clearWebUiToken(storage)
  redirectToLogin()
}

/**
 * JSON 请求统一在这里完成头部、序列化、错误解析和认证失效处理。
 */
export async function requestJson<T>(
  input: RequestInfo | URL,
  options: WebUiJsonRequestOptions = {},
): Promise<T> {
  const {
    method = 'GET',
    body,
    headers,
    authenticated = true,
    includeJson = true,
    storage = window.sessionStorage,
    fetchImpl = fetch,
    redirectToLogin,
  } = options
  const requestHeaders = {
    ...buildAuthHeaders(includeJson, storage),
    ...(headers || {}),
  }
  const response = await fetchImpl(input, {
    method,
    headers: requestHeaders,
    body: body === undefined ? undefined : toJsonBody(body),
  })
  if (response.status === 401 || response.status === 403) {
    if (authenticated) {
      handleUnauthorized(storage, redirectToLogin)
      throw new Error('请重新登录')
    }
    throw new Error('密码错误，请重试')
  }
  const payload = await readJsonResponse<T>(response)
  if (!response.ok) {
    const payloadMessage = typeof payload === 'object' && payload && 'message' in payload
      ? String((payload as {message?: string}).message || '')
      : ''
    throw new Error(normalizeVisibleMessage(payloadMessage, '请求失败，请稍后重试'))
  }
  return payload as T
}
