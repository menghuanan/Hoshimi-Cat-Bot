import { renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmationProvider } from '../contexts/ConfirmationContext'
import { useLogs } from './useLogs'
import { useRuntimeSummary } from './useRuntimeSummary'
import { useSettingsFiles } from './useSettingsFiles'
import { useSubscriptions } from './useSubscriptions'
import { useThemePreference } from './useThemePreference'

const createJsonResponse = (status: number, payload: unknown) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => payload,
})

const renderWithConfirmationProvider = (callback: Parameters<typeof renderHook>[0]) => {
  return renderHook(callback, {
    wrapper: ({children}) => <ConfirmationProvider>{children}</ConfirmationProvider>,
  })
}

describe('webui domain hooks', () => {
  it('useRuntimeSummary should call the runtime summary endpoint', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {appVersion: '1.0.0'}))

    renderHook(() => useRuntimeSummary({fetchImpl, pollIntervalMs: 100_000}))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1))
    expect(fetchImpl).toHaveBeenCalledWith('/api/runtime/summary', expect.objectContaining({
      headers: expect.objectContaining({
        Accept: 'application/json',
      }),
    }))
  })

  /**
   * 首页等价字段从原始 DTO 收敛成稳定视图模型，页面不重复理解后端结构。
   */
  it('useRuntimeSummary should expose dashboard parity fields from the runtime payload', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {
      lifecycleState: 'RUNNING',
      uptimeSeconds: 3661,
      appVersion: '2.0.0',
      platformReady: true,
      subscriptionCount: 8,
      dynamicSubscriptionCount: 5,
      bangumiSubscriptionCount: 3,
      groupCount: 2,
      account: {loggedIn: true, uid: 12345, cookieConfigured: true},
      webSocket: {connected: true, reconnectAttempts: 1, activeSessionCount: 2, transports: ['onebot11'], note: 'ready'},
      todayPushStats: {date: '2026-05-23', total: 6, dynamic: 4, live: 1, liveClose: 1, failed: 0, lastSuccessAtEpochMillis: 1_700_000_300_000},
      recentPushRecords: [{timestampEpochMillis: 1_700_000_000_000, type: 'dynamic', typeLabel: '动态', success: true, statusLabel: '成功', summary: '测试推送', target: '群 100'}],
      host: {
        startedAtEpochMillis: 1_700_000_000_000,
        systemTimeEpochMillis: 1_700_000_300_000,
        systemLoadAverage: 0.75,
        cpuUsagePercent: 12.5,
        memory: {usedBytes: 1024, totalBytes: 2048, usagePercent: 50},
        storage: {usedBytes: 4096, totalBytes: 8192, usagePercent: 50},
        docker: {detected: true, evidence: 'container'},
      },
    }))

    const {result} = renderHook(() => useRuntimeSummary({fetchImpl, pollIntervalMs: 100_000}))

    await waitFor(() => expect(result.current.dashboard).toMatchObject({
      appVersion: '2.0.0',
      lifecycleState: 'RUNNING',
      uptimeSeconds: 3661,
      startedAtEpochMillis: 1_700_000_000_000,
      systemTimeEpochMillis: 1_700_000_300_000,
      systemLoadAverage: 0.75,
      cpuUsagePercent: 12.5,
      memoryUsagePercent: 50,
      storageUsagePercent: 50,
      dockerDetected: true,
      accountLoggedIn: true,
      accountUid: 12345,
      platformReady: true,
      webSocketConnected: true,
      todayPushTotal: 6,
      recentPushRecordsCount: 1,
    }))
  })

  it('useSettingsFiles should keep snapshot tokens and proxyUpdateMode when saving BiliConfig', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config') && (!init || init.method === 'GET')) {
        return Promise.resolve(createJsonResponse(200, {
          sourceFile: 'BiliConfig.yml',
          snapshotToken: 'bili-snapshot',
          fields: [],
        }))
      }
      if (url.includes('/api/config/bot') && (!init || init.method === 'GET')) {
        return Promise.resolve(createJsonResponse(200, {
          sourceFile: 'bot.yml',
          snapshotToken: 'bot-snapshot',
          fields: [],
        }))
      }
      if (url.includes('/api/config/bili-config') && init?.method === 'POST') {
        return Promise.resolve(createJsonResponse(200, {snapshotToken: 'next-token', success: true}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const user = userEvent.setup()
    const {result} = renderWithConfirmationProvider(() => useSettingsFiles({fetchImpl})) as {
      result: {current: ReturnType<typeof useSettingsFiles>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/config/bot', expect.any(Object)))
    const savePromise = result.current.saveBili({
      snapshotToken: 'bili-snapshot',
      proxyText: 'http://proxy.example:8080',
      currentProxies: ['http://old.example:8080'],
    })

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('密码确认')
    await user.type(screen.getByLabelText('确认密码'), 'secret-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await savePromise

    const postCall = fetchImpl.mock.calls.find(([url, init]) => String(url).includes('/api/config/bili-config') && init?.method === 'POST')
    expect(postCall).toBeTruthy()
    expect(JSON.parse(String(postCall?.[1]?.body))).toMatchObject({
      snapshotToken: 'bili-snapshot',
      confirmationPassword: 'secret-password',
      proxyUpdateMode: 'replace',
    })
  })

  it('useSubscriptions should include confirmationPassword when creating a subscription', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions') && (!init || init.method === 'GET')) {
        return Promise.resolve(createJsonResponse(200, {items: []}))
      }
      if (url.endsWith('/api/subscriptions') && init?.method === 'POST') {
        return Promise.resolve(createJsonResponse(200, {success: true}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const user = userEvent.setup()
    const {result} = renderWithConfirmationProvider(() => useSubscriptions({fetchImpl})) as {
      result: {current: ReturnType<typeof useSubscriptions>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/subscriptions', expect.any(Object)))
    const createPromise = result.current.saveSubscription({
      type: 'dynamic',
      uid: '123',
      targetGroup: '456',
    })

    await user.type(await screen.findByLabelText('确认密码'), 'create-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await createPromise

    const postCall = fetchImpl.mock.calls.find(([url, init]) => String(url).endsWith('/api/subscriptions') && init?.method === 'POST')
    expect(JSON.parse(String(postCall?.[1]?.body))).toMatchObject({
      type: 'dynamic',
      uid: '123',
      targetGroup: '456',
      confirmationPassword: 'create-password',
    })
  })

  it('useLogs should go through centered and high-risk confirmation when clearing logs', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/logs/sources')) {
        return Promise.resolve(createJsonResponse(200, {sources: [{id: 'source-1'}]}))
      }
      if (url.includes('/api/logs/source-1?tail=500')) {
        return Promise.resolve(createJsonResponse(200, {text: 'line-1'}))
      }
      if (url.endsWith('/api/logs/source-1/clear') && init?.method === 'POST') {
        return Promise.resolve(createJsonResponse(200, {success: true}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const user = userEvent.setup()
    const {result} = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/logs/sources', expect.any(Object)))
    const clearPromise = result.current.clearCurrentLog('source-1')

    const centeredDialog = await screen.findByRole('dialog')
    expect(centeredDialog).toHaveTextContent('确认操作')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await screen.findByRole('dialog')
    await user.type(screen.getByLabelText('确认密码'), 'log-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await clearPromise

    const postCall = fetchImpl.mock.calls.find(([url, init]) => String(url).includes('/api/logs/source-1/clear') && init?.method === 'POST')
    expect(JSON.parse(String(postCall?.[1]?.body))).toMatchObject({
      sourceId: 'source-1',
      confirmationPassword: 'log-password',
    })
  })

  /**
   * 日志 hook 负责解析日志行并提供过滤、搜索和导出文本，页面不重复拆日志格式。
   */
  it('useLogs should parse and filter log rows for the logs page', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/logs/sources')) {
        return Promise.resolve(createJsonResponse(200, {sources: [{id: 'source-1'}, {id: 'source-2'}]}))
      }
      if (url.includes('/api/logs/source-1?tail=500')) {
        return Promise.resolve(createJsonResponse(200, {text: '[INFO] [core] boot ok\n[WARN] [push] push slow\nplain line'}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const {result} = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
    }

    await waitFor(() => expect(result.current.levels).toEqual(['INFO', 'WARN']))
    expect(result.current.modules).toEqual(['core', 'push'])

    result.current.setLevelFilter('WARN')
    result.current.setModuleFilter('push')
    result.current.setKeyword('slow')

    await waitFor(() => expect(result.current.filteredRows.map((row) => row.raw)).toEqual(['[WARN] [push] push slow']))
    expect(result.current.exportFilteredRows()).toContain('[WARN] [push] push slow')
  })

  it('useThemePreference should persist the selected preference locally', () => {
    const {result} = renderHook(() => useThemePreference())

    result.current.setPreference('dark')

    expect(window.localStorage.getItem('dynamic_bot_webui_theme')).toBe('dark')
  })
})
