/**
 * 高频写操作遇到 401/403 时，页面统一转成更容易理解的密码错误提示。
 */
export function formatPasswordErrorMessage(error: unknown, fallbackMessage: string): string {
  const message = error instanceof Error ? error.message : String(error || '')
  if (/\bHTTP\s*(401|403)\b/.test(message)) {
    return '密码错误'
  }
  return message || fallbackMessage
}

/**
 * 登录页使用更直接的密码错误文案，避免把 HTTP 状态码暴露给普通用户。
 */
export function formatLoginErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error || '')
  if (/\bHTTP\s*(401|403)\b/.test(message)) {
    return '密码错误，请重试'
  }
  return message || '登录失败'
}
