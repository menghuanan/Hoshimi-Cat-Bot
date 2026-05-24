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
      lifecycleState: 'Bot运行中',
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
      },
    }))

    const {result} = renderHook(() => useRuntimeSummary({fetchImpl, pollIntervalMs: 100_000}))

    await waitFor(() => expect(result.current.dashboard).toMatchObject({
      appVersion: '2.0.0',
      lifecycleState: 'Bot运行中',
      uptimeSeconds: 3661,
      startedAtEpochMillis: 1_700_000_000_000,
      systemTimeEpochMillis: 1_700_000_300_000,
      systemLoadAverage: 0.75,
      cpuUsagePercent: 12.5,
      memoryUsagePercent: 50,
      storageUsagePercent: 50,
      storageUsedBytes: 4096,
      storageTotalBytes: 8192,
      accountLoggedIn: true,
      accountUid: 12345,
      platformReady: true,
      webSocketConnected: true,
      todayPushTotal: 6,
      recentPushRecordsCount: 1,
    }))
  })

  /**
   * 首页状态卡颜色由运行态视图模型统一决定，避免页面组件重复理解后端状态枚举。
   */
  it('useRuntimeSummary should derive dashboard status tones from runtime health', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(createJsonResponse(200, {
        lifecycleState: 'RUNNING',
        account: {loggedIn: true, uid: 12345, cookieConfigured: true},
        webSocket: {connected: true, reconnectAttempts: 0, activeSessionCount: 1, transports: ['onebot11']},
      }))
      .mockResolvedValueOnce(createJsonResponse(200, {
        lifecycleState: 'STARTING',
        account: {loggedIn: false, uid: null, cookieConfigured: false},
        webSocket: {connected: false, reconnectAttempts: 1, activeSessionCount: 0, transports: []},
      }))
      .mockResolvedValueOnce(createJsonResponse(200, {
        lifecycleState: 'STOPPING',
        account: {loggedIn: false, uid: null, cookieConfigured: false},
        webSocket: {connected: false, reconnectAttempts: 1, activeSessionCount: 0, transports: []},
      }))
      .mockResolvedValueOnce(createJsonResponse(200, {
        lifecycleState: 'STOPPED',
        account: {loggedIn: false, uid: null, cookieConfigured: false},
        webSocket: {connected: false, reconnectAttempts: 1, activeSessionCount: 0, transports: []},
      }))

    const {result} = renderHook(() => useRuntimeSummary({fetchImpl, pollIntervalMs: 100_000}))

    await waitFor(() => expect(result.current.dashboard).toMatchObject({
      lifecycleTone: 'emerald',
      accountTone: 'emerald',
      webSocketTone: 'sky',
    }))

    await result.current.refresh()
    await waitFor(() => expect(result.current.dashboard).toMatchObject({
      lifecycleTone: 'sky',
      accountTone: 'rose',
      webSocketTone: 'rose',
    }))

    await result.current.refresh()
    await waitFor(() => expect(result.current.dashboard.lifecycleTone).toBe('sky'))

    await result.current.refresh()
    await waitFor(() => expect(result.current.dashboard.lifecycleTone).toBe('rose'))
  })

  /**
   * 首页只渲染清洗后的最近推送记录，不把后端原始 target 字段继续带到 dashboard 视图里。
   */
  it('useRuntimeSummary should sanitize recent push records before dashboard rendering', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {
      recentPushRecords: [{
        timestampEpochMillis: 1_700_000_000_000,
        type: 'dynamic',
        typeLabel: '动态',
        success: true,
        statusLabel: '成功',
        summary: '测试推送',
        target: 'onebot11:group:10001',
      }],
    }))

    const {result} = renderHook(() => useRuntimeSummary({fetchImpl, pollIntervalMs: 100_000}))

    await waitFor(() => expect(result.current.dashboard.recentPushRecords).toEqual([{
      timestampEpochMillis: 1_700_000_000_000,
      typeLabel: '动态',
      statusLabel: '成功',
      subscriptionInfo: '测试推送',
    }]))
    expect(result.current.dashboard.recentPushRecords[0]).not.toHaveProperty('target')
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

  /**
   * 分组新增表单使用显示层字段名，hook 必须转换成后端新增订阅 DTO。
   */
  it('useSubscriptions should convert group create fields before posting to backend', async () => {
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
      type: 'group',
      groupName: '测试分组',
      groupUid: '1001',
      groupTarget: '2001',
    })

    await user.type(await screen.findByLabelText('确认密码'), 'group-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await createPromise

    const postCall = fetchImpl.mock.calls.find(([url, init]) => String(url).endsWith('/api/subscriptions') && init?.method === 'POST')
    expect(JSON.parse(String(postCall?.[1]?.body))).toEqual({
      type: 'group',
      groupName: '测试分组',
      uid: '1001',
      targetGroup: '2001',
      confirmationPassword: 'group-password',
    })
  })

  /**
   * 订阅嵌套编辑器的写入同样属于高风险操作，hook 必须统一补齐 confirmationPassword。
   */
  it('useSubscriptions should gate nested editor writes with confirmation passwords', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions') && (!init || init.method === 'GET')) {
        return Promise.resolve(createJsonResponse(200, {items: []}))
      }
      if (url.includes('/api/subscriptions/sub-1/')) {
        return Promise.resolve(createJsonResponse(200, {success: true}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const user = userEvent.setup()
    const {result} = renderWithConfirmationProvider(() => useSubscriptions({fetchImpl})) as {
      result: {current: ReturnType<typeof useSubscriptions>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/subscriptions', expect.any(Object)))
    const saveFilterPromise = result.current.saveFilter('sub-1', {
      key: '',
      kind: 'regex',
      mode: 'black',
      content: '广告',
    })

    await user.type(await screen.findByLabelText('确认密码'), 'filter-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    await saveFilterPromise

    const togglePromise = result.current.toggleRandomTemplate('sub-1', true)
    await user.type(await screen.findByLabelText('确认密码'), 'random-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    await togglePromise

    const filterCall = fetchImpl.mock.calls.find(([url, init]) => String(url).endsWith('/api/subscriptions/sub-1/filters') && init?.method === 'POST')
    expect(JSON.parse(String(filterCall?.[1]?.body))).toMatchObject({
      kind: 'regex',
      mode: 'black',
      content: '广告',
      confirmationPassword: 'filter-password',
    })

    const randomCall = fetchImpl.mock.calls.find(([url, init]) => String(url).endsWith('/api/subscriptions/sub-1/templates/random') && init?.method === 'POST')
    expect(JSON.parse(String(randomCall?.[1]?.body))).toEqual({
      enabled: true,
      confirmationPassword: 'random-password',
    })
  })

  it('useLogs should clear the visible log window without calling the backend clear route', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/logs/sources')) {
        return Promise.resolve(createJsonResponse(200, {sources: [{id: 'source-1'}]}))
      }
      if (url.includes('/api/logs/source-1?tail=500')) {
        return Promise.resolve(createJsonResponse(200, {text: 'line-1'}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const {result} = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/logs/sources', expect.any(Object)))
    await waitFor(() => expect(result.current.rows).toEqual(['line-1']))

    await result.current.clearCurrentLog('source-1')

    expect(result.current.rows).toEqual([])
    expect(fetchImpl.mock.calls.some(([url]) => String(url).includes('/clear'))).toBe(false)
  })

  /**
   * 自动刷新偏好写入 cookie，用户再次打开日志页时继续沿用。
   */
  it('useLogs should persist auto refresh preference in a cookie', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/logs/sources')) {
        return Promise.resolve(createJsonResponse(200, {sources: [{id: 'source-1'}]}))
      }
      if (url.includes('/api/logs/source-1?tail=500')) {
        return Promise.resolve(createJsonResponse(200, {text: 'line-1'}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })
    document.cookie = 'dynamic_bot_webui_logs_auto_refresh=; Max-Age=0; path=/'
    const first = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
      unmount: () => void
    }

    await waitFor(() => expect(first.result.current.sourceId).toBe('source-1'))
    first.result.current.setAutoRefresh(true)
    expect(document.cookie).toContain('dynamic_bot_webui_logs_auto_refresh=true')
    first.unmount()

    const second = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
    }
    expect(second.result.current.autoRefresh).toBe(true)
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
    expect(result.current.exportFilteredRows()).toBe('[WARN] [push] push slow')
  })

  /**
   * 文件日志使用 logback 默认格式，级别和模块筛选必须从真实日志行中提取。
   */
  it('useLogs should parse logback file rows for level and module filters', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/logs/sources')) {
        return Promise.resolve(createJsonResponse(200, {sources: [{id: 'app'}]}))
      }
      if (url.includes('/api/logs/app?tail=500')) {
        return Promise.resolve(createJsonResponse(200, {text: [
          '2026-05-23 10:00:00.000 [main] INFO  top.bilibili.Main - boot ok',
          '2026-05-23 10:00:01.000 [main] WARN  top.bilibili.webui.WebUiManager - reload failed',
        ].join('\n')}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const {result} = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
    }

    await waitFor(() => expect(result.current.levels).toEqual(['INFO', 'WARN']))
    expect(result.current.modules).toEqual(['top.bilibili.Main', 'top.bilibili.webui.WebUiManager'])

    result.current.setLevelFilter('WARN')
    result.current.setModuleFilter('top.bilibili.webui.WebUiManager')

    await waitFor(() => expect(result.current.filteredRows.map((row) => row.raw)).toEqual([
      '2026-05-23 10:00:01.000 [main] WARN  top.bilibili.webui.WebUiManager - reload failed',
    ]))
  })

  /**
   * 日志初次加载必须用来源接口返回的第一个 sourceId 读取窗口，不能等用户手动切换。
   */
  it('useLogs should load the first source window during initial reload', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/logs/sources')) {
        return Promise.resolve(createJsonResponse(200, {sources: [{id: 'app'}, {id: 'error'}]}))
      }
      if (url.includes('/api/logs/app?tail=500')) {
        return Promise.resolve(createJsonResponse(200, {text: '2026-05-23 INFO [core] boot ok'}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const {result} = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
    }

    await waitFor(() => expect(result.current.sourceId).toBe('app'))
    await waitFor(() => expect(result.current.filteredRows.map((row) => row.raw)).toEqual(['2026-05-23 INFO [core] boot ok']))
    expect(fetchImpl).toHaveBeenCalledWith('/api/logs/app?tail=500', expect.any(Object))
  })

  it('useThemePreference should persist the selected preference locally', () => {
    const {result} = renderHook(() => useThemePreference())

    result.current.setPreference('dark')

    expect(window.localStorage.getItem('dynamic_bot_webui_theme')).toBe('dark')
  })

  /**
   * 主题偏好需要同步应用到 DOM 和 cookie，服务端静态壳层才能维持一致主题。
   */
  it('useThemePreference should apply theme preference to document state', () => {
    const {result} = renderHook(() => useThemePreference())

    result.current.setPreference('dark')

    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true)
    expect(document.cookie).toContain('dynamic_bot_webui_theme=dark')
  })
})
