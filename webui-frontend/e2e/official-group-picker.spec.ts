import { expect, test } from '@playwright/test'
import { installWebUiApiMock } from './fixtures/webuiApiMock'

/** 打包页面验证双列多选、窄屏溢出和暗色样式，并检查真实前端提交的多群契约。 */
test('selects official QQ groups from the bundled subscription dialog', async ({page}) => {
  await installWebUiApiMock(page)
  const groups = ['A'.repeat(32), 'B'.repeat(32)]
  await page.route('**/api/platform/groups', async (route) => {
    await route.fulfill({json: {enabled: true, generation: 'generation-a', connected: true, nameStatus: 'FORBIDDEN', stateStatus: 'AVAILABLE', storageError: null,
      items: groups.map((openId, index) => ({subject: `qq_official:group:${openId}`, openId, name: index === 0 ? '星见猫测试群' : '', alias: index === 1 ? '游戏交流群' : '', joined: true, acceptsProactive: true, observedAt: 1, refreshedAt: 2}))}})
  })
  await page.addInitScript(() => window.localStorage.setItem('hoshimi_cat_bot_webui_theme', 'dark'))
  await page.goto('/login')
  await page.getByLabel('WebUI 密码').fill('secret-password')
  await page.getByRole('button', {name: '登录', exact: true}).click()
  await page.goto('/subscriptions')
  await page.getByRole('button', {name: '新增订阅', exact: true}).click()
  const dialog = page.getByRole('dialog', {name: '新增订阅'})
  await dialog.getByLabel('UID', {exact: true}).fill('123')
  await dialog.getByRole('button', {name: '目标群聊', exact: true}).click()
  await dialog.getByRole('checkbox', {name: /星见猫测试群/}).check()
  await dialog.getByRole('checkbox', {name: /游戏交流群/}).check()
  await expect(dialog.getByRole('button', {name: '目标群聊', exact: true})).toHaveText(/已选择 2 个群聊/)
  const name = await dialog.getByText('星见猫测试群', {exact: true}).boundingBox()
  const id = await dialog.getByText(groups[0], {exact: true}).boundingBox()
  expect(id!.x).toBeGreaterThan(name!.x)
  await page.screenshot({path: '../.tmp/group-picker-desktop.png'})
  await page.setViewportSize({width: 390, height: 844})
  await expect(dialog).toBeVisible()
  const width = await page.evaluate(() => ({scroll: document.documentElement.scrollWidth, viewport: window.innerWidth}))
  expect(width.scroll).toBeLessThanOrEqual(width.viewport)
  await page.screenshot({path: '../.tmp/group-picker-mobile.png'})
  const submitted = page.waitForRequest((request) => request.url().endsWith('/api/subscriptions') && request.method() === 'POST')
  await dialog.getByRole('button', {name: '确认新增'}).click()
  expect((await submitted).postDataJSON()).toEqual({type: 'dynamic', uid: '123', targetGroups: groups.map((id) => `qq_official:group:${id}`), directoryGeneration: 'generation-a'})
})
