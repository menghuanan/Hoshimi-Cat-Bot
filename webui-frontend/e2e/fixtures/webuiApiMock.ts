import type { Page, Route } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const runtimeRoot = fileURLToPath(new URL('../../../src/main/resources/webui/react/', import.meta.url))
const shellHtml = readFileSync(join(runtimeRoot, 'index.html'), 'utf8')

/**
 * Bundled React 静态资源按扩展名返回内容类型，确保浏览器真实加载 JS/CSS。
 */
function contentTypeForPath(pathname: string): string {
  if (pathname.endsWith('.js')) {
    return 'text/javascript; charset=utf-8'
  }
  if (pathname.endsWith('.css')) {
    return 'text/css; charset=utf-8'
  }
  if (extname(pathname) === '.html') {
    return 'text/html; charset=utf-8'
  }
  return 'application/octet-stream'
}

/**
 * E2E mock 只模拟 WebUI 公开路由和 API 契约，敏感字段使用固定测试值。
 */
export async function installWebUiApiMock(page: Page) {
  let authenticated = false
  const protectedShellPaths = new Set(['/', '/settings', '/subscriptions', '/logs'])

  await page.route('http://webui-react.test/**', async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname

    if (pathname.startsWith('/api/')) {
      await fulfillApi(route, pathname, request.postDataJSON?.())
      return
    }

    if (pathname.startsWith('/assets/')) {
      const assetName = pathname.replace('/assets/', '')
      await route.fulfill({
        status: 200,
        contentType: contentTypeForPath(pathname),
        body: readFileSync(join(runtimeRoot, 'assets', assetName)),
      })
      return
    }

    if (protectedShellPaths.has(pathname) && !authenticated) {
      await route.fulfill({
        status: 200,
        contentType: 'text/html; charset=utf-8',
        body: '<!doctype html><title>redirect</title><script>location.replace("/login")</script>',
      })
      return
    }

    await route.fulfill({status: 200, contentType: 'text/html; charset=utf-8', body: shellHtml})
  })

  /**
   * API 响应集中在闭包内，登录状态只影响本轮浏览器测试，不泄露到其他测试。
   */
  async function fulfillApi(route: Route, pathname: string, body: unknown) {
    if (pathname === '/api/auth/login') {
      const password = typeof body === 'object' && body && 'password' in body ? String((body as {password?: unknown}).password) : ''
      if (password !== 'secret-password') {
        await route.fulfill({status: 401, contentType: 'application/json', body: JSON.stringify({message: '密码错误'})})
        return
      }
      authenticated = true
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({token: 'test-token', mustChangePassword: false})})
      return
    }
    if (pathname === '/api/auth/session') {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({authenticated, mustChangePassword: false})})
      return
    }
    if (pathname === '/api/auth/logout') {
      authenticated = false
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({authenticated: false})})
      return
    }
    if (pathname === '/api/runtime/summary') {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(runtimeSummary())})
      return
    }
    if (pathname === '/api/config/bili-config') {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(configSnapshot('BiliConfig.yml', 'bili-token'))})
      return
    }
    if (pathname === '/api/config/bot') {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(configSnapshot('bot.yml', 'bot-token'))})
      return
    }
    if (pathname === '/api/subscriptions') {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(subscriptionList())})
      return
    }
    if (pathname.endsWith('/filters')) {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({filters: [{key: 'filter-1', kind: 'regex', mode: 'black', content: '广告'}]})})
      return
    }
    if (pathname.endsWith('/templates')) {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({templates: [{key: 'tpl-1', type: 'dynamic', name: '默认模板', content: '{{title}}'}], randomEnabled: true})})
      return
    }
    if (pathname.endsWith('/atall')) {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({items: [{key: 'atall-1', type: 'Dynamic', groups: ['10001']}]})})
      return
    }
    if (pathname.endsWith('/theme')) {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({color: '#33aaff'})})
      return
    }
    if (pathname === '/api/logs/sources') {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({sources: [{id: 'main'}]})})
      return
    }
    if (pathname === '/api/logs/main') {
      await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({text: '[INFO] [core] boot ok\n[WARN] [push] slow'})})
      return
    }
    await route.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify({success: true})})
  }
}

/**
 * 运行态 mock 覆盖首页全部核心摘要，避免浏览器测试只验证空壳。
 */
function runtimeSummary() {
  return {
    lifecycleState: 'RUNNING',
    uptimeSeconds: 7200,
    appVersion: '2.0.0-test',
    platformReady: true,
    subscriptionCount: 3,
    dynamicSubscriptionCount: 1,
    bangumiSubscriptionCount: 1,
    groupCount: 1,
    account: {loggedIn: true, uid: 2233, cookieConfigured: true},
    webSocket: {connected: true, reconnectAttempts: 0, activeSessionCount: 1, transports: ['onebot11']},
    todayPushStats: {date: '2026-05-23', total: 5, dynamic: 3, live: 1, liveClose: 1, failed: 0},
    recentPushRecords: [{timestampEpochMillis: 1_700_000_000_000, type: 'dynamic', typeLabel: '动态', success: true, statusLabel: '成功', summary: '测试推送', target: 'onebot11:group:10001'}],
    host: {
      startedAtEpochMillis: 1_700_000_000_000,
      systemTimeEpochMillis: 1_700_007_200_000,
      systemLoadAverage: 0.5,
      cpuUsagePercent: 25,
      memory: {usedBytes: 1024, totalBytes: 2048, usagePercent: 50},
      storage: {usedBytes: 2048, totalBytes: 4096, usagePercent: 50},
      docker: {detected: true, evidence: 'container'},
    },
  }
}

/**
 * 配置快照包含普通字段和写入专用字段，验证 React 不把敏感值回填到输入。
 */
function configSnapshot(sourceFile: string, snapshotToken: string) {
  return {
    sourceFile,
    snapshotToken,
    fields: [
      {key: 'platform.onebot11.host', label: 'OneBot11 主机', value: '127.0.0.1', capability: 'EDITABLE', editable: true},
      {key: 'platform.onebot11.token', label: 'OneBot11 Token', value: 'SECRET_TOKEN', capability: 'MASKED', editable: true},
      {key: 'proxyConfig.proxy', label: '代理地址', value: 'http://secret-proxy', capability: 'MASKED', editable: true},
    ],
  }
}

/**
 * 订阅列表 mock 覆盖动态、番剧和分组三类卡片以及嵌套编辑入口。
 */
function subscriptionList() {
  return {
    items: [
      {
        id: 'dynamic-1',
        kind: 'dynamic',
        title: '测试动态订阅',
        identifierLabel: 'UID: 2233',
        sourceId: 2233,
        tags: ['动态'],
        targetSectionTitle: '推送目标',
        targets: ['10001'],
        filterInfo: '1 条过滤器',
        filterCount: 1,
        templateNames: ['默认模板'],
        templateCount: 1,
        atAllInfo: '全部动态',
        themeColor: '#33aaff',
      },
    ],
  }
}
