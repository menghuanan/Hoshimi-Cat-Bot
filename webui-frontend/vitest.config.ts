import { defineConfig } from 'vitest/config'

// Vitest 只负责前端源码和 API 合约，不参与 Vite 生产构建产物生成。
export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
  },
})
