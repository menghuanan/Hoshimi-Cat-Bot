const cjkPattern = /[\u3400-\u9fff]/
const httpStatusPattern = /\bHTTP\s*\d{3}\b/i

/**
 * 页面只展示能直接读懂的中文结果；纯英文或 HTTP 状态码一律降级为统一提示。
 */
export function normalizeVisibleMessage(error: unknown, fallbackMessage: string): string {
  const message = error instanceof Error ? error.message : String(error || '')
  const text = message.trim()
  if (!text) {
    return fallbackMessage
  }
  if (text === '请重新登录' || text === '密码错误' || text === '密码错误，请重试') {
    return text
  }
  if (text === '请求失败，请稍后重试') {
    return fallbackMessage ? `${fallbackMessage}，请稍后重试` : text
  }
  if (httpStatusPattern.test(text)) {
    return fallbackMessage ? `${fallbackMessage}，请稍后重试` : '请稍后重试'
  }
  if (!cjkPattern.test(text) && /[A-Za-z]/.test(text)) {
    return fallbackMessage ? `${fallbackMessage}，请稍后重试` : '请稍后重试'
  }
  return text
}

/**
 * 高频写操作遇到 401/403 时，页面统一转成更容易理解的密码错误提示。
 */
export function formatPasswordErrorMessage(error: unknown, fallbackMessage: string): string {
  const message = error instanceof Error ? error.message : String(error || '')
  if (/^\s*(401|403)\s*$/.test(message) || httpStatusPattern.test(message)) {
    return '密码错误'
  }
  const normalized = normalizeVisibleMessage(error, fallbackMessage)
  return normalized.includes('密码错误') ? '密码错误' : normalized
}

/**
 * 登录页使用更直接的密码错误文案，避免把 HTTP 状态码暴露给普通用户。
 */
export function formatLoginErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error || '')
  if (/^\s*(401|403)\s*$/.test(message) || httpStatusPattern.test(message) || message === '请重新登录') {
    return '密码错误，请重试'
  }
  return normalizeVisibleMessage(error, '登录失败')
}
