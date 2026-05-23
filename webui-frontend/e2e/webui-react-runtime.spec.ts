import { expect, test } from '@playwright/test'
import { installWebUiApiMock } from './fixtures/webuiApiMock'

/**
 * 每个浏览器用例都重建 mock，保证登录状态和 API 请求记录互不串扰。
 */
test.beforeEach(async ({page}) => {
  await installWebUiApiMock(page)
})

test('redirects protected direct routes to the login flow before authentication', async ({page}) => {
  for (const path of ['/', '/settings', '/subscriptions', '/logs']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByRole('heading', {name: '登录'})).toBeVisible()
  }
})

test('logs in and keeps direct React routes stable after refresh', async ({page}) => {
  await page.goto('/login')
  await page.getByLabel('WebUI 密码').fill('wrong-password')
  await page.getByRole('button', {name: '登录'}).click()
  await expect(page.getByText('密码错误，请重试')).toBeVisible()

  await page.getByLabel('WebUI 密码').fill('secret-password')
  await page.getByRole('button', {name: '登录'}).click()
  await expect(page.getByRole('heading', {name: '运行概览'})).toBeVisible()
  await expect(page.getByText('2.0.0-test')).toBeVisible()

  for (const [path, heading] of [
    ['/settings', '系统配置'],
    ['/subscriptions', '订阅管理'],
    ['/logs', '日志'],
    ['/', '运行概览'],
  ] as const) {
    await page.goto(path)
    await expect(page.getByRole('heading', {name: heading}).first()).toBeVisible()
    await page.reload()
    await expect(page.getByRole('heading', {name: heading}).first()).toBeVisible()
  }

  await page.goto('/subscriptions')
  await expect(page.getByText('群聊：1072150397、1245551')).toBeVisible()
  await expect(page.getByText('2 个过滤器')).toBeVisible()
  await expect(page.getByText('2 个模板')).toBeVisible()
  await expect(page.getByText('onebot11:group:1072150397')).toHaveCount(0)
  await expect(page.getByText('group:1072150397 类型黑名单: 空，正则黑名单: 测试')).toHaveCount(0)
})

test('left aligns settings tabs and narrows settings cards without changing vertical flow', async ({page}) => {
  await page.setViewportSize({width: 1440, height: 1000})
  await page.goto('/login')
  await page.getByLabel('WebUI 密码').fill('secret-password')
  await page.getByRole('button', {name: '登录'}).click()
  await expect(page.getByRole('heading', {name: '运行概览'})).toBeVisible()

  const pageLayouts: Array<{path: string, selector: string}> = [
    {path: '/', selector: '[data-page="home"]'},
    {path: '/settings', selector: '[data-page="settings"]'},
    {path: '/subscriptions', selector: '[data-page="subscriptions"]'},
    {path: '/logs', selector: '[data-page="logs"]'},
  ]

  for (const layout of pageLayouts) {
    await page.goto(layout.path)
    const pageBox = await page.locator(layout.selector).boundingBox()
    expect(pageBox).not.toBeNull()
    expect(pageBox?.x).toBeGreaterThan(240)
    expect(pageBox?.width).toBeLessThanOrEqual(1280)
  }

  await page.goto('/settings')
  const tabsBox = await page.getByRole('button', {name: '对接配置'}).locator('..').boundingBox()
  const contentBox = await page.locator('[data-page="settings"]').boundingBox()
  expect(tabsBox).not.toBeNull()
  expect(contentBox).not.toBeNull()
  expect(Math.abs((tabsBox?.x || 0) - (contentBox?.x || 0))).toBeLessThan(2)

  const platformTypeBox = await page.getByLabel('平台类型').boundingBox()
  const adapterBox = await page.getByLabel('适配器').boundingBox()
  expect(platformTypeBox).not.toBeNull()
  expect(adapterBox).not.toBeNull()
  expect((platformTypeBox?.width || 0) / (contentBox?.width || 1)).toBeGreaterThan(0.7)
  expect((platformTypeBox?.width || 0) / (contentBox?.width || 1)).toBeLessThan(0.8)
  expect(Math.abs((platformTypeBox?.x || 0) - (adapterBox?.x || 0))).toBeLessThan(2)
  expect(adapterBox?.y).toBeGreaterThan((platformTypeBox?.y || 0) + (platformTypeBox?.height || 0))
})

test('keeps API and static asset routes out of the React fallback', async ({page}) => {
  await page.goto('/login')
  const runtimeResponse = await page.evaluate(async () => {
    const response = await fetch('/api/runtime/summary')
    return {contentType: response.headers.get('content-type') || '', payload: await response.json()}
  })
  expect(runtimeResponse.contentType).toContain('application/json')
  expect(runtimeResponse.payload).toMatchObject({appVersion: '2.0.0-test'})

  const scriptResponse = await page.evaluate(async () => {
    const response = await fetch('/assets/app.js')
    return {contentType: response.headers.get('content-type') || '', text: await response.text()}
  })
  expect(scriptResponse.contentType).toContain('text/javascript')
  expect(scriptResponse.text).toContain('createRoot')
})

test('shows mobile navigation, high-risk confirmation, and page content under a narrow viewport', async ({page}) => {
  await page.setViewportSize({width: 390, height: 844})
  await page.goto('/login')
  await page.getByLabel('WebUI 密码').fill('secret-password')
  await page.getByRole('button', {name: '登录'}).click()

  await expect(page.getByRole('button', {name: '系统配置'})).toBeVisible()
  await page.getByRole('button', {name: '系统配置'}).click()
  await expect(page.getByRole('heading', {name: '系统配置'}).first()).toBeVisible()
  await page.getByRole('button', {name: '保存'}).click()
  await expect(page.getByRole('dialog', {name: '密码确认'})).toBeVisible()
  await expect(page.getByLabel('确认密码')).toBeVisible()
})
