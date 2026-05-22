import { afterEach, beforeEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import '@testing-library/jest-dom/vitest'

/**
 * 默认 fetch 只用于没有显式注入 fetchImpl 的组件测试，避免页面 hook 触发真实网络访问。
 */
const defaultFetch = vi.fn(async (input: RequestInfo | URL) => {
  const url = String(input)
  if (url.includes('/api/runtime/summary')) {
    return {ok: true, status: 200, json: async () => ({appVersion: '1.0.0'})}
  }
  if (url.includes('/api/config/bili-config')) {
    return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: 'bili-snapshot', fields: []})}
  }
  if (url.includes('/api/config/bot')) {
    return {ok: true, status: 200, json: async () => ({sourceFile: 'bot.yml', snapshotToken: 'bot-snapshot', fields: []})}
  }
  if (url.includes('/api/subscriptions')) {
    return {ok: true, status: 200, json: async () => ({items: []})}
  }
  if (url.includes('/api/logs/sources')) {
    return {ok: true, status: 200, json: async () => ({sources: [{id: 'source-1'}]})}
  }
  if (url.includes('/api/logs/source-1?tail=')) {
    return {ok: true, status: 200, json: async () => ({text: 'line-1'})}
  }
  return {ok: true, status: 200, json: async () => ({success: true})}
})

// 每个测试后清理 mock，避免 fetch、location 和 storage 状态互相污染。
beforeEach(() => {
  vi.stubGlobal('fetch', defaultFetch)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
