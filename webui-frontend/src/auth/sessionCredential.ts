let sessionPassword = ''

/**
 * 登录密码只保留在当前页面的 JavaScript 内存中，供既有写接口透明完成后端确认。
 */
export function rememberSessionPassword(password: string): void {
  sessionPassword = password
}

/**
 * 写操作读取当前登录凭据，不把密码复制到浏览器持久化存储或 URL。
 */
export function readSessionPassword(): string {
  return sessionPassword
}

/**
 * 登出或改密后立即清除内存凭据，避免旧密码继续参与后续请求。
 */
export function clearSessionPassword(): void {
  sessionPassword = ''
}
