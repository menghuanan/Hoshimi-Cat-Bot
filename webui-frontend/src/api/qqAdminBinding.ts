import { requestJson } from './http'

export type QQAdminBindingSession = {
  sessionId: string
  code: string | null
  expiresAtEpochMillis: number
  phase: 'WAITING' | 'SUCCEEDED' | 'EXPIRED' | 'FAILED'
}

/** 创建或复用当前绑定会话；统一请求层负责 session cookie 和 CSRF。 */
export function startQQAdminBinding(): Promise<QQAdminBindingSession> {
  return requestJson('/api/qq-admin-binding', {method: 'POST'})
}

/** 轮询只携带会话 ID，绑定码不进入 URL 或浏览器持久化存储。 */
export function loadQQAdminBinding(sessionId: string): Promise<QQAdminBindingSession> {
  return requestJson(`/api/qq-admin-binding/${encodeURIComponent(sessionId)}`)
}
