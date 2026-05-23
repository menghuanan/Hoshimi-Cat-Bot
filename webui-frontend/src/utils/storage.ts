/**
 * WebUI token 只通过这一层读写，避免页面代码直接散落 sessionStorage 访问。
 */
export type TokenStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

const webUiTokenKey = 'webuiToken'

/**
 * 读取当前 WebUI token；没有 token 时返回空串，调用方再决定是否发起匿名请求。
 */
export function readWebUiToken(storage: TokenStorage = window.sessionStorage): string {
  return storage.getItem(webUiTokenKey) || ''
}

/**
 * 保存 token 的写入口只接受非空值，空值由显式清理函数处理。
 */
export function writeWebUiToken(token: string, storage: TokenStorage = window.sessionStorage): void {
  if (token) {
    storage.setItem(webUiTokenKey, token)
  }
}

/**
 * 清理 token 的读写状态，供登出和认证失效场景复用。
 */
export function clearWebUiToken(storage: TokenStorage = window.sessionStorage): void {
  storage.removeItem(webUiTokenKey)
}
