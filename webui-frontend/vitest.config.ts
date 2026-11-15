import { defineConfig } from 'vitest/config'

// Vitest 只负责前端源码和 API 合约，不参与 Vite 生产构建产物生成。
export default defineConfig({
  test: {
    environment: 'jsdom',
    // 运行时守卫使用 node:test 独立执行，不加载浏览器 jsdom 环境。
    exclude: ['e2e/**', 'node_modules/**', 'dist/**', 'scripts/**'],
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
  },
})
