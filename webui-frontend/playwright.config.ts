import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright 只跑 bundled React runtime 的本地 mock 路径，避免依赖生产 WebUI 端口或真实凭据。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  reporter: 'list',
  use: {
    baseURL: 'http://webui-react.test',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        channel: process.env.PLAYWRIGHT_CHROMIUM_CHANNEL || undefined,
      },
    },
  ],
})
