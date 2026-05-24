/**
 * Cookie 读取只保留前端安全相关的值，避免把认证 token 继续放进 sessionStorage。
 */
export function readCookieValue(name: string, cookieSource = document.cookie): string {
  const prefix = `${name}=`
  const entry = cookieSource.split(';').map((value) => value.trim()).find((value) => value.startsWith(prefix))
  if (!entry) {
    return ''
  }
  const rawValue = entry.slice(prefix.length)
  return rawValue ? decodeURIComponent(rawValue) : ''
}

const webUiCsrfCookieName = 'dynamic_bot_webui_csrf'

/**
 * WebUI unsafe 请求从同源 CSRF cookie 读取令牌，再统一写到 X-CSRF-Token 头里。
 */
export function readWebUiCsrfToken(cookieSource = document.cookie): string {
  return readCookieValue(webUiCsrfCookieName, cookieSource)
}
