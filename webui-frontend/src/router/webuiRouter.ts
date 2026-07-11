/**
 * WebUI 仅使用轻量路径与 hash 路由，避免现在就引入更重的路由库。
 */
export type WebUiPageName = 'home' | 'settings' | 'subscriptions' | 'logs' | 'login'

/**
 * 登录页保留独立路径；受保护页面同时识别 Ktor 直达 pathname 和旧 hash 导航。
 */
export function readWebUiPage(pathname = window.location.pathname, hash = window.location.hash): WebUiPageName {
  if (pathname.endsWith('/login')) {
    return 'login'
  }
  // 直接路径来自 Ktor React fallback，刷新时必须回到对应主页面。
  const directPath = pathname.replace(/\/+$/, '')
  if (directPath === '/settings' || directPath === '/subscriptions' || directPath === '/logs') {
    return directPath.slice(1) as WebUiPageName
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
  // 登录成功进入主壳时只替换浏览器地址，不触发整页重载或丢失内存中的登录凭据。
  if (window.location.pathname.endsWith('/login')) {
    window.history.replaceState({}, '', page === 'home' ? '/' : `/#${page}`)
    return
  }
  window.location.hash = page === 'home' ? '' : page
}
