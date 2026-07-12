import { requestJson, type WebUiJsonRequestOptions } from './http'
import type { WebUiBiliLoginSession } from '../types/biliLogin'

/** 创建请求携带当前内存确认密码，后端成功后返回本次唯一二维码图片。 */
export async function startBiliLogin(
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiBiliLoginSession> {
  return requestJson<WebUiBiliLoginSession>('/api/bili-login/sessions', {
    ...options,
    method: 'POST',
    body: {confirmationPassword},
  })
}

/** 状态轮询只读取指定不可猜测会话 ID，不重复下载二维码。 */
export async function fetchBiliLoginSession(
  sessionId: string,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiBiliLoginSession> {
  return requestJson<WebUiBiliLoginSession>(`/api/bili-login/sessions/${encodeURIComponent(sessionId)}`, {
    ...options,
    method: 'GET',
    includeJson: false,
  })
}

/** 弹窗关闭时使用 DELETE 释放仍可取消的全局会话。 */
export async function cancelBiliLogin(
  sessionId: string,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiBiliLoginSession> {
  return requestJson<WebUiBiliLoginSession>(`/api/bili-login/sessions/${encodeURIComponent(sessionId)}`, {
    ...options,
    method: 'DELETE',
    includeJson: false,
  })
}
