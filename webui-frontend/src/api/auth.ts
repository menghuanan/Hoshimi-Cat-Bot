import { requestJson, type WebUiJsonRequestOptions } from './http'
import type { WebUiAuthResponse } from '../types/auth'

/**
 * 登录结果复用同一套导航策略，避免登录页和会话恢复页各写一套跳转判断。
 */
export type WebUiAuthNavigationHandlers = {
  onShell?: (response: WebUiAuthResponse) => void
  onChangePassword?: (response: WebUiAuthResponse) => void
  onLogin?: () => void
}

/**
 * 登录只负责提交密码并返回会话状态，不再把 token 写进前端存储。
 */
export async function loginWithPassword(
  password: string,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiAuthResponse> {
  const response = await requestJson<WebUiAuthResponse>('/api/auth/login', {
    ...options,
    authenticated: false,
    method: 'POST',
    body: {password},
    includeJson: true,
  })
  return response
}

/**
 * 会话探测走同一套 cookie 会话和 JSON 解析逻辑，保证登录页与主壳共享认证结果。
 */
export async function restoreSession(
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiAuthResponse> {
  return requestJson<WebUiAuthResponse>('/api/auth/session', {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * 改密请求只提交当前密码和新密码，其他登录态由同源 cookie 维持。
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiAuthResponse> {
  return requestJson<WebUiAuthResponse>('/api/auth/change-password', {
    ...options,
    method: 'POST',
    body: {currentPassword, newPassword},
    includeJson: true,
    authenticated: true,
  })
}

/**
 * 登出依赖同源 cookie 会话，调用方直接回到登录页即可。
 */
export async function logout(options: WebUiJsonRequestOptions = {}): Promise<WebUiAuthResponse> {
  return requestJson<WebUiAuthResponse>('/api/auth/logout', {
    ...options,
    method: 'POST',
    includeJson: false,
    authenticated: true,
  })
}

/**
 * 登录结果决定是进入主壳还是留在改密流程。
 */
export function applyLoginResult(
  response: WebUiAuthResponse,
  handlers: WebUiAuthNavigationHandlers = {},
): 'shell' | 'change-password' {
  if (response.mustChangePassword) {
    handlers.onChangePassword?.(response)
    return 'change-password'
  }
  handlers.onShell?.(response)
  return 'shell'
}

/**
 * 会话结果同样复用 shell / 改密 / 登录三态，避免页面各自散落 if-else。
 */
export function applyAuthSession(
  response: WebUiAuthResponse,
  handlers: WebUiAuthNavigationHandlers = {},
): 'shell' | 'change-password' | 'login' {
  if (!response.authenticated) {
    handlers.onLogin?.()
    return 'login'
  }
  if (response.mustChangePassword) {
    handlers.onChangePassword?.(response)
    return 'change-password'
  }
  handlers.onShell?.(response)
  return 'shell'
}
