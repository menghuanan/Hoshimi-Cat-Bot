/**
 * WebUI 仅使用轻量路径与 hash 路由，避免现在就引入更重的路由库。
 */
export type WebUiPageName = 'home' | 'settings' | 'subscriptions' | 'logs' | 'login'

/**
 * 登录页仍保留独立路径，其余页面暂时由 hash 决定，和当前 Ktor 门禁兼容。
 */
export function readWebUiPage(pathname = window.location.pathname, hash = window.location.hash): WebUiPageName {
  if (pathname.endsWith('/login')) {
    return 'login'
  }
  const rawHash = hash.replace(/^#/, '')
  if (rawHash === 'settings' || rawHash === 'subscriptions' || rawHash === 'logs') {
    return rawHash
  }
  return 'home'
}

/**
 * Shell 内部导航只改 hash，登录和登出则回到独立路径。
 */
export function writeWebUiPage(page: WebUiPageName): void {
  if (page === 'login') {
    window.location.assign('/login')
    return
  }
  window.location.hash = page === 'home' ? '' : page
}
