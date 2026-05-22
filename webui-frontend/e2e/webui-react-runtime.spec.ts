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
  await expect(page.getByText('HTTP 401')).toBeVisible()

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
