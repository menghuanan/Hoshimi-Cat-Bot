import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import { formatSaveResultMessage } from './settings/settingsSaveResult'

/**
 * 组件测试需要稳定的浏览器路径，先切到目标 path 再渲染 App。
 */
function renderAtPath(path: string) {
  window.history.pushState({}, '', path)
  return render(<App />)
}

/**
 * 组件测试用完整运行态响应覆盖默认 mock，验证首页真正消费后端摘要字段。
 */
function stubRuntimeSummary(payload: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/api/runtime/summary')) {
      return {ok: true, status: 200, json: async () => payload}
    }
    return {ok: true, status: 200, json: async () => ({success: true})}
  }))
}

/**
 * 订阅编辑相关测试需要可控异步，手动控制 resolve 顺序来复现旧数据回写。
 */
function createDeferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return {promise, resolve, reject}
}

/**
 * 设置页测试统一返回 BiliData 快照和热重载 job，避免每个用例都重复模拟批量保存契约。
 */
function createSettingsResponse(url: string, init?: RequestInit, options: {
  batchOk?: boolean
  batchStatus?: number
  batchMessage?: string
  botSnapshotToken?: string
  onBatchBody?: (body: Record<string, Record<string, unknown>>) => void
} = {}) {
  if (url.includes('/api/config/bili-data') && (!init || init.method === 'GET')) {
    return {
      ok: true,
      status: 200,
      json: async () => ({
        sourceFile: 'BiliData.yml',
        snapshotToken: 'data-token',
        fields: [
          {key: 'linkParseBlacklistContacts', label: '链接解析黑名单', value: '', capability: 'EDITABLE', editable: true},
        ],
      }),
    }
  }
  if (url.includes('/api/config/save-batch') && init?.method === 'POST') {
    const ok = options.batchOk !== false
    const body = parseJsonBody(init.body)
    options.onBatchBody?.(body)
    const files = [
      body.biliConfig ? 'BILI_CONFIG' : null,
      body.biliData ? 'BILI_DATA' : null,
      body.botConfig ? 'BOT_CONFIG' : null,
    ].filter(Boolean)
    return {
      ok,
      status: options.batchStatus ?? (ok ? 202 : 401),
      json: async () => ok
        ? {jobId: 'job-1', phase: 'QUEUED', files}
        : {message: options.batchMessage || 'bad password'},
    }
  }
  if (url.includes('/api/config/save-jobs/job-1')) {
    return {
      ok: true,
      status: 200,
      json: async () => ({
        jobId: 'job-1',
        phase: 'APPLIED',
        files: ['BILI_CONFIG', 'BILI_DATA', 'BOT_CONFIG'],
        message: '保存成功，配置已热重载',
        outcomes: [
          {file: 'BILI_CONFIG', result: {success: true, snapshotToken: 'bili-token-2'}},
          {file: 'BILI_DATA', result: {success: true, snapshotToken: 'data-token-2'}},
          {file: 'BOT_CONFIG', result: {success: true, snapshotToken: options.botSnapshotToken || 'bot-token-2'}},
        ],
      }),
    }
  }
  return null
}

/**
 * 测试 helper 只解析本地 mock 的 JSON body，避免断言代码重复 try/catch。
 */
function parseJsonBody(body: BodyInit | null | undefined): Record<string, Record<string, unknown>> {
  try {
    return JSON.parse(String(body || '{}'))
  } catch {
    return {}
  }
}

/**
 * 批量保存测试只关心 bot.yml 子 payload，统一取出嵌套对象避免继续断言旧单文件 POST。
 */
function collectBotBatchPayloads(): {
  payloads: Array<Record<string, unknown>>
  handleBatchBody: (body: Record<string, Record<string, unknown>>) => void
} {
  const payloads: Array<Record<string, unknown>> = []
  return {
    payloads,
    handleBatchBody: (body) => {
      if (body.botConfig) {
        payloads.push(body.botConfig)
      }
    },
  }
}

describe('webui shell routing', () => {
  it('renders the dashboard shell with the core navigation pages', () => {
    renderAtPath('/')

    expect(screen.getByRole('heading', {name: '动态机器人 WebUI'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '首页'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '系统配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '订阅管理'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '日志'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '管理员', expanded: false})).toBeInTheDocument()
    expect(document.querySelector('header')).toBeNull()
  })

  it('switches pages when the shell navigation is used', () => {
    renderAtPath('/')

    fireEvent.click(screen.getByRole('button', {name: '系统配置'}))

    expect(screen.queryByText('写入设置')).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: '保存'})).toBeInTheDocument()
  })

  /**
   * 右侧内容区承担页面切换动效，侧边栏保持独立不参与路由淡入。
   */
  it('wraps routed content in a transition surface without moving the sidebar', () => {
    renderAtPath('/')

    const routeSurface = document.querySelector('[data-route-transition]')
    expect(routeSurface).not.toBeNull()
    expect(routeSurface).toHaveClass('page-transition-surface')
    expect(routeSurface).toHaveAttribute('data-route-page', 'home')
    expect(document.querySelector('aside [data-route-transition]')).toBeNull()

    fireEvent.click(screen.getByRole('button', {name: '系统配置'}))

    expect(document.querySelector('[data-route-transition]')).toHaveAttribute('data-route-page', 'settings')
    expect(screen.getByRole('button', {name: '系统配置'})).toHaveClass('nav-item-active')
  })

  /**
   * Ktor 直接服务 /settings、/subscriptions 和 /logs，React 路由也必须识别这些刷新入口。
   */
  it('renders protected direct path routes without requiring hash navigation', () => {
    const settingsRender = renderAtPath('/settings')
    expect(screen.queryByText('写入设置')).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: '保存'})).toBeInTheDocument()
    settingsRender.unmount()

    const subscriptionsRender = renderAtPath('/subscriptions')
    expect(screen.getByRole('button', {name: '新增订阅'})).toBeInTheDocument()
    subscriptionsRender.unmount()

    renderAtPath('/logs')
    expect(screen.getAllByRole('heading', {name: '实时日志'}).length).toBeGreaterThan(0)
  })

  /**
   * 设置页必须按旧 WebUI 分区渲染，并保证敏感字段只作为空输入写入。
   */
  it('renders settings tabs and keeps sensitive readback values out of inputs', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'BiliConfig.yml',
            snapshotToken: 'bili-token',
            fields: [
              {key: 'accountConfig.cookie', label: 'Cookie', value: 'SECRET_COOKIE', capability: 'MASKED', editable: true},
              {key: 'proxyConfig.proxy', label: '代理地址', value: 'http://secret-proxy', capability: 'MASKED', editable: true},
              {key: 'enableConfig.drawEnable', label: '动态渲染', value: 'true', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'platform.onebot11.token', label: 'OneBot11 Token', value: 'SECRET_TOKEN', capability: 'MASKED', editable: true},
              {key: 'platform.onebot11.host', label: 'OneBot11 主机', value: '127.0.0.1', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    renderAtPath('/#settings')

    expect(await screen.findByRole('button', {name: '对接配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '功能开关'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'B站配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '轮询配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '渲染配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '消息配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '管理员', expanded: false})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '翻译配置'})).toBeInTheDocument()
    expect(screen.queryByText('写入设置')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'B站配置'}))
    expect(await screen.findByLabelText('Cookie')).toHaveValue('')
    expect(screen.getByLabelText('代理地址')).toHaveValue('')
    expect(screen.queryByDisplayValue('SECRET_COOKIE')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('http://secret-proxy')).not.toBeInTheDocument()
    expect(screen.queryByText('BiliConfig 快照')).not.toBeInTheDocument()
    expect(screen.queryByText('bot.yml 快照')).not.toBeInTheDocument()
    expect(screen.queryByText('敏感输入')).not.toBeInTheDocument()
    expect(screen.queryByText(/仅填写需要替换的敏感值/)).not.toBeInTheDocument()
    expect(screen.queryByText(/当前分区包含需要重启后生效的配置/)).not.toBeInTheDocument()
    expect(screen.queryByText(/写入专用/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: '对接配置'}))
    expect(await screen.findByLabelText('OneBot11 Token')).toHaveValue('')
    expect(screen.queryByDisplayValue('SECRET_TOKEN')).not.toBeInTheDocument()
  })

  /**
   * 字段说明和布尔开关使用统一视觉层级，警告性质说明不能混在普通灰字里。
   */
  it('renders settings helper text and boolean fields with visual helper treatments', async () => {
    renderAtPath('/#settings')

    const platformWarning = await screen.findByText('当前 QQ 官方机器人没有做适配，不推荐使用。')
    expect(platformWarning.closest('[data-field-helper-tone="warning"]')).not.toBeNull()
    expect(platformWarning.closest('[data-field-helper-tone="warning"]')).toHaveClass('settings-warning-helper')

    const tlsToggle = await screen.findByLabelText('启用 TLS')
    expect(tlsToggle).toHaveClass('toggle-input')
    expect(tlsToggle.closest('[data-toggle-shell]')).not.toBeNull()

    const portHelper = await screen.findByText('填写 OneBot11 实际开放端口。')
    expect(portHelper.closest('[data-field-helper-tone="muted"]')).not.toBeNull()
    expect(portHelper.closest('[data-field-helper-tone="muted"]')).toHaveClass('settings-muted-helper')
  })

  /**
   * 配置保存必须把后端业务结果反馈给用户，不能把 success=false 的响应当成成功提交。
   */
  it('formats detailed settings save result messages from the backend response', () => {
    expect(formatSaveResultMessage([{
      success: false,
      message: 'oneBot11Port is invalid',
      validationErrors: ['oneBot11Port is invalid'],
    }])).toBe('保存失败：请检查填写内容后重试')

    expect(formatSaveResultMessage([{
      success: true,
      message: 'bot.yml saved',
    }])).toBe('保存成功')
  })

  /**
   * 保存成功后页面应保留刚写入的值，不再被旧快照回刷。
   */
  it('keeps edited settings values visible after a successful save', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init)
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'BiliConfig.yml',
            snapshotToken: 'bili-token',
            fields: [
              {key: 'accountConfig.followGroup', label: '关注分组', value: '旧分组', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      if (url.includes('/api/config/bot') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'platform.type', label: '平台类型', value: 'onebot11', capability: 'EDITABLE', editable: true},
              {key: 'webui.enabled', label: '启用 WebUI', value: 'false', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')

    fireEvent.click(await screen.findByRole('button', {name: 'B站配置'}))
    const followGroup = await screen.findByLabelText('关注分组')
    await user.clear(followGroup)
    await user.type(followGroup, '新分组')
    await user.click(screen.getByRole('button', {name: '保存'}))

    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await waitFor(() => expect(screen.getByLabelText('关注分组')).toHaveValue('新分组'))
    const toast = (await screen.findByText(/保存成功/)).closest('[data-toast]')
    expect(toast).not.toBeNull()
    expect(toast).toHaveClass('toast-success')
    expect(toast?.parentElement).toHaveAttribute('data-toast-viewport', 'true')
  })

  /**
   * 字段改回原值后不应进入高风险确认或批量保存，避免无有效变更也触发热重载。
   */
  it('skips settings save when edited values return to their original snapshot value', async () => {
    const batchBodies: Array<Record<string, Record<string, unknown>>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init, {
        onBatchBody: (body) => batchBodies.push(body),
      })
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config') && (!init || init.method === 'GET')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: 'bili-token', fields: []})}
      }
      if (url.includes('/api/config/bot') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'platform.onebot11.host', label: 'OneBot11 主机', value: '127.0.0.1', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')

    const hostInput = await screen.findByLabelText('OneBot11 主机')
    await user.clear(hostInput)
    await user.type(hostInput, '127.0.0.2')
    await user.clear(hostInput)
    await user.type(hostInput, '127.0.0.1')
    await user.click(screen.getByRole('button', {name: '保存'}))

    expect(await screen.findByText('没有检测到配置变更')).toBeInTheDocument()
    expect(screen.queryByLabelText('确认密码')).not.toBeInTheDocument()
    expect(batchBodies).toHaveLength(0)
  })

  /**
   * 高风险保存如果被错误密码拒绝，页面应该提示密码错误而不是 HTTP 状态码。
   */
  it('shows a friendly password error when settings save is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init, {
        batchOk: false,
        batchStatus: 401,
        batchMessage: 'bad password',
      })
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'BiliConfig.yml',
            snapshotToken: 'bili-token',
            fields: [
              {key: 'accountConfig.followGroup', label: '关注分组', value: '旧分组', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      if (url.includes('/api/config/bot') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'platform.type', label: '平台类型', value: 'onebot11', capability: 'EDITABLE', editable: true},
              {key: 'webui.enabled', label: '启用 WebUI', value: 'false', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')

    fireEvent.click(await screen.findByRole('button', {name: 'B站配置'}))
    const followGroup = await screen.findByLabelText('关注分组')
    await user.clear(followGroup)
    await user.type(followGroup, '新分组')
    await user.click(screen.getByRole('button', {name: '保存'}))

    await user.type(await screen.findByLabelText('确认密码'), 'wrong-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    expect(await screen.findByText('密码错误')).toBeInTheDocument()
    expect(screen.queryByText('HTTP 401')).not.toBeInTheDocument()
  })

  /**
   * 设置保存必须先拦截明显非法输入，避免用户先输确认密码才看到字段错误。
   */
  it('validates settings before opening the high-risk password dialog', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'BiliConfig.yml',
            snapshotToken: 'bili-token',
            fields: [],
          }),
        }
      }
      if (url.includes('/api/config/bot') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'platform.onebot11.host', label: 'OneBot11 主机', value: '127.0.0.1', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderAtPath('/#settings')

    const hostInput = await screen.findByLabelText('OneBot11 主机')
    await user.clear(hostInput)
    await user.click(screen.getByRole('button', {name: '保存'}))

    expect(await screen.findByText('OneBot11 主机必须填写')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', {name: '密码确认'})).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).includes('/api/config/bot') && init?.method === 'POST')).toBe(false)
  })

  it('keeps a dense React layout for dashboard cards and account modal', () => {
    renderAtPath('/')

    expect(screen.getByText('运行概览')).toBeInTheDocument()
    expect(screen.queryByText('配置入口')).not.toBeInTheDocument()
    expect(screen.queryByText('日志窗口')).not.toBeInTheDocument()
    expect(screen.queryByText('最近推送')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '打开配置'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '打开订阅'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '查看日志'})).not.toBeInTheDocument()
    expect(screen.queryByText(/实时摘要由/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: '管理员', expanded: false}))
    fireEvent.click(screen.getByRole('button', {name: '修改密码'}))

    expect(screen.getByRole('dialog', {name: '修改密码'})).toBeInTheDocument()
    expect(screen.getByLabelText('当前密码')).toBeInTheDocument()
  })

  /**
   * 首页必须展示运行、账号、平台、推送和宿主指标，避免 React 壳层只剩版本卡片。
   */
  it('renders dashboard runtime summary fields from the API payload', async () => {
    stubRuntimeSummary({
      lifecycleState: 'RUNNING',
      uptimeSeconds: 7200,
      appVersion: '2.0.0',
      platformReady: true,
      subscriptionCount: 9,
      dynamicSubscriptionCount: 6,
      bangumiSubscriptionCount: 3,
      groupCount: 4,
      account: {loggedIn: true, uid: 12345, cookieConfigured: true},
      webSocket: {connected: true, reconnectAttempts: 3, activeSessionCount: 2, transports: ['onebot11'], note: 'ready'},
      todayPushStats: {date: '2026-05-23', total: 7, dynamic: 5, live: 1, liveClose: 1, failed: 0, lastSuccessAtEpochMillis: 1_700_000_000_000},
      recentPushRecords: [{timestampEpochMillis: new Date(2026, 11, 12, 12, 31).getTime(), type: 'LIVE', typeLabel: '直播', success: true, statusLabel: '成功', summary: '傲慢的小肉包 | 群 100 | 直播间 123', target: '群 100'}],
      host: {
        startedAtEpochMillis: 1_700_000_000_000,
        systemTimeEpochMillis: 1_700_007_200_000,
        systemLoadAverage: 0.5,
        cpuUsagePercent: 12,
        memory: {usedBytes: 130 * 1024 ** 3, totalBytes: 256 * 1024 ** 3, usagePercent: 51},
        storage: {usedBytes: 120 * 1024 ** 3, totalBytes: 256 * 1024 ** 3, usagePercent: 50},
      },
    })

    renderAtPath('/')

    await waitFor(() => expect(screen.getByText('2.0.0')).toBeInTheDocument())
    expect(screen.getByText('UID 12345')).toBeInTheDocument()
    expect(screen.getByText('Bot运行中')).toBeInTheDocument()
    expect(screen.getByText('已连接')).toBeInTheDocument()
    expect(screen.getByText('会话：2 / 重连：3')).toBeInTheDocument()
    expect(screen.getByText('7 条')).toBeInTheDocument()
    const header = screen.getByTestId('recent-push-header')
    expect(within(header).getByText('订阅名称')).toBeInTheDocument()
    expect(within(header).getByText('推送类型')).toBeInTheDocument()
    expect(within(header).getByText('状态')).toBeInTheDocument()
    expect(within(header).getByText('时间')).toBeInTheDocument()
    expect(screen.getByText('直播')).toBeInTheDocument()
    expect(screen.getByText('成功')).toBeInTheDocument()
    expect(screen.getByText('傲慢的小肉包')).toBeInTheDocument()
    expect(screen.queryByText(/群 100/)).not.toBeInTheDocument()
    expect(screen.getByText('2026年12月12日 12：31')).toBeInTheDocument()
    expect(screen.getByText('120G/256G')).toBeInTheDocument()
    expect(screen.queryByText('50%')).not.toBeInTheDocument()
    expect(document.querySelector('progress.resource-meter')?.getAttribute('style')).toBeNull()
    expect(screen.getByLabelText('CPU')).toHaveClass('resource-meter-safe')
    expect(screen.getByLabelText('内存')).toHaveClass('resource-meter-safe')
    expect(screen.getByLabelText('存储')).toHaveClass('resource-meter-safe')
  })

  /**
   * 资源进度条按阈值变色，并为数字滚动保留稳定显示节点。
   */
  it('classifies runtime resource meters by usage thresholds', async () => {
    stubRuntimeSummary({
      host: {
        cpuUsagePercent: 59,
        memory: {usagePercent: 75},
        storage: {usedBytes: 900 * 1024 ** 3, totalBytes: 1000 * 1024 ** 3, usagePercent: 90},
      },
    })

    renderAtPath('/')

    expect(await screen.findByText('59.0%')).toHaveAttribute('data-count-up-value', '59')
    expect(screen.getByText('75.0%')).toHaveAttribute('data-count-up-value', '75')
    expect(screen.getByText('900G/1000G')).toHaveAttribute('data-count-up-value', '90')
    expect(screen.getByLabelText('CPU')).toHaveClass('resource-meter-safe')
    expect(screen.getByLabelText('内存')).toHaveClass('resource-meter-warn')
    expect(screen.getByLabelText('存储')).toHaveClass('resource-meter-danger')
  })

  /**
   * 最近推送记录行只显示值本身，时间值靠右贴近卡片边缘，其余列保留纵向左对齐。
   */
  it('renders recent push rows without repeated field labels', async () => {
    stubRuntimeSummary({
      recentPushRecords: [{timestampEpochMillis: new Date(2026, 11, 12, 12, 31).getTime(), type: 'LIVE', typeLabel: '直播', success: true, statusLabel: '成功', summary: '傲慢的小肉包 | 群 100'}],
    })

    renderAtPath('/')

    const row = await screen.findByTestId('recent-push-row-0')
    const cells = within(row).getAllByTestId('recent-push-cell')

    expect(cells).toHaveLength(4)
    expect(within(row).queryByText('订阅信息')).not.toBeInTheDocument()
    expect(within(row).queryByText('类型')).not.toBeInTheDocument()
    expect(within(row).queryByText('状态')).not.toBeInTheDocument()
    expect(within(row).queryByText('时间')).not.toBeInTheDocument()
    expect(row).toHaveClass('py-2')
    expect(cells[0]).toHaveClass('text-left')
    expect(cells[1]).toHaveClass('text-left')
    expect(cells[2]).toHaveClass('text-left')
    expect(cells[3]).toHaveClass('text-right')
    expect(screen.getByTestId('recent-push-header')).toHaveClass('grid-cols-[8.75rem_4rem_1.25rem_2rem_minmax(0,1fr)_10.5rem]')
    expect(screen.getByTestId('recent-push-header')).toHaveClass('gap-1')
    expect(screen.getByTestId('recent-push-header')).toHaveClass('px-2')
    expect(within(screen.getByTestId('recent-push-header')).getByText('时间')).toHaveClass('text-left')
  })

  /**
   * 首页最近推送记录只保留前 7 条，避免列表撑满卡片后影响运行信息区的扫描密度。
   */
  it('limits recent push records to seven rows', async () => {
    stubRuntimeSummary({
      recentPushRecords: Array.from({length: 8}, (_, index) => ({
        timestampEpochMillis: new Date(2026, 11, 12, 12, 31 + index).getTime(),
        type: 'LIVE',
        typeLabel: index % 2 === 0 ? '直播' : '动态',
        success: true,
        statusLabel: '成功',
        summary: `订阅${index + 1} | 群 100`,
      })),
    })

    renderAtPath('/')

    expect(await screen.findByTestId('recent-push-row-0')).toBeInTheDocument()
    expect(screen.getByTestId('recent-push-row-6')).toBeInTheDocument()
    expect(screen.queryByTestId('recent-push-row-7')).not.toBeInTheDocument()
  })

  /**
   * 最近推送记录卡片必须始终保留固定表头，空记录时把占位文案放在内容区正中央。
   */
  it('keeps the recent push card header visible and centers the empty state', async () => {
    stubRuntimeSummary({
      recentPushRecords: [],
    })

    renderAtPath('/')

    const emptyState = await screen.findByText('暂无最近推送记录')
    const header = screen.getByTestId('recent-push-header')

    expect(within(header).getByText('订阅名称')).toBeInTheDocument()
    expect(within(header).getByText('推送类型')).toBeInTheDocument()
    expect(within(header).getByText('状态')).toBeInTheDocument()
    expect(within(header).getByText('时间')).toBeInTheDocument()
    expect(within(header).getByText('订阅名称')).toHaveClass('whitespace-nowrap')
    expect(within(header).getByText('推送类型')).toHaveClass('whitespace-nowrap')
    expect(within(header).getByText('状态')).toHaveClass('whitespace-nowrap')
    expect(within(header).getByText('时间')).toHaveClass('whitespace-nowrap')
    expect(emptyState).toHaveClass('grid')
    expect(emptyState).toHaveClass('place-items-center')
    expect(emptyState).toHaveClass('flex-1')
  })

  /**
   * 对接配置按平台和 WebUI 开关显示不同字段，避免把无效配置项全部摊开。
   */
  it('renders integration settings by platform and WebUI enabled state', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: 'bili-token', fields: []})}
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'platform.type', label: '平台类型', value: 'onebot11', capability: 'EDITABLE', editable: true},
              {key: 'webui.enabled', label: '启用 WebUI', value: 'false', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#settings')

    expect(await screen.findByText('平台配置')).toBeInTheDocument()
    expect(screen.getByText('WebUI 配置')).toBeInTheDocument()
    expect(screen.getByLabelText('OneBot11 主机')).toBeInTheDocument()
    expect(screen.queryByLabelText('QQ App ID')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('WebUI 主机')).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: '对接配置'}).parentElement).toHaveClass('justify-start')
    expect(screen.getByRole('heading', {name: '系统配置'})).toBeInTheDocument()
    expect(screen.getByText('保存后自动热重载生效')).toBeInTheDocument()
    expect(screen.getByText('保存后自动热重载生效')).toHaveClass('text-slate-600')
    expect(screen.getByText('当前 QQ 官方机器人没有做适配，不推荐使用。')).toBeInTheDocument()
    expect(screen.getByText('默认关闭，按需开启独立 WebUI。')).toBeInTheDocument()
    expect(screen.queryByText('保存后需要重启')).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: '保存'}).parentElement?.parentElement).toHaveClass('justify-between')

    const platformGroup = screen.getByText('平台配置').closest('fieldset')
    expect(platformGroup).not.toBeNull()
    expect(platformGroup?.querySelector('legend')).toHaveTextContent('平台配置')
    expect(platformGroup).toHaveClass('mx-auto')
    expect(platformGroup).toHaveClass('md:w-3/4')
    expect(platformGroup?.querySelector('div')).toHaveClass('grid')
    expect(platformGroup?.querySelector('div')).not.toHaveClass('xl:grid-cols-2')

    await user.selectOptions(screen.getByLabelText('平台类型'), 'qq_official')
    expect(screen.getByLabelText('QQ App ID')).toBeInTheDocument()
    expect(screen.queryByLabelText('OneBot11 主机')).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('启用 WebUI'))
    expect(screen.getByLabelText('WebUI 主机')).toBeInTheDocument()
    expect(screen.getByLabelText('WebUI 端口')).toBeInTheDocument()
    expect(screen.getByLabelText('会话有效秒数')).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '对接配置'}).parentElement).toHaveClass('max-w-7xl')
  })

  /**
   * WebUI 主机设为 0.0.0.0 时，页面必须明确显示对外暴露警告，并在保存前再次确认风险。
   */
  it('warns when webui.host is set to 0.0.0.0 and mirrors the risk in the confirmation dialog', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: 'bili-token', fields: []})}
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'platform.type', label: '平台类型', value: 'onebot11', capability: 'EDITABLE', editable: true},
              {key: 'webui.enabled', label: '启用 WebUI', value: 'true', capability: 'EDITABLE', editable: true},
              {key: 'webui.host', label: 'WebUI 主机', value: '0.0.0.0', capability: 'EDITABLE', editable: true},
              {key: 'webui.port', label: 'WebUI 端口', value: '18080', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#settings')

    expect(await screen.findByLabelText('WebUI 主机')).toHaveValue('0.0.0.0')
    expect(screen.getByText(/0\.0\.0\.0/)).toBeInTheDocument()
    expect(screen.getByText(/对外暴露/)).toBeInTheDocument()

    await user.clear(screen.getByLabelText('WebUI 端口'))
    await user.type(screen.getByLabelText('WebUI 端口'), '18081')
    await user.click(screen.getByRole('button', {name: '保存'}))
    expect(await screen.findByRole('dialog', {name: '密码确认'})).toHaveTextContent(/0\.0\.0\.0/)
  })

  /**
   * 群普通管理员使用群聊和个人 QQ 双栏卡片直接展示已有映射。
   */
  it('renders group admin settings as paired group and user inputs', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: 'bili-token', fields: []})}
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'admins', label: 'admins', value: JSON.stringify([{groupId: 124515, userIds: [1245512]}]), capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    renderAtPath('/#settings')
    fireEvent.click(await screen.findByRole('button', {name: '管理员', pressed: false}))

    expect(await screen.findByLabelText('群聊')).toHaveValue('124515')
    expect(screen.getByLabelText('个人QQ号')).toHaveValue('1245512')
    expect(screen.queryByText('群聊：124515 管理员：1245512')).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: '删除'})).toBeInTheDocument()
  })

  /**
   * 已有群普通管理员直接以可编辑卡片呈现，删除卡片后保存应提交删除后的管理员列表。
   */
  it('saves deleted existing group admin cards without requiring the stage button', async () => {
    const {payloads: botPostBodies, handleBatchBody} = collectBotBatchPayloads()
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init, {onBatchBody: handleBatchBody})
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: '', fields: []})}
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token-1',
            fields: [
              {
                key: 'admins',
                label: 'admins',
                value: JSON.stringify([
                  {groupId: 124515, userIds: [1245512]},
                  {groupId: 998877, userIds: [665544]},
                ]),
                capability: 'EDITABLE',
                editable: true,
              },
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')
    await user.click(await screen.findByRole('button', {name: '管理员', pressed: false}))

    expect(screen.queryByText('群聊：124515 管理员：1245512')).not.toBeInTheDocument()
    expect(screen.getAllByLabelText('群聊')).toHaveLength(2)
    expect(screen.getAllByLabelText('个人QQ号')).toHaveLength(2)

    await user.click(screen.getAllByRole('button', {name: '删除'})[0])
    await user.click(screen.getByRole('button', {name: '保存'}))
    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await waitFor(() => expect(botPostBodies).toHaveLength(1))
    expect(botPostBodies[0].admins).toEqual([
      {
        groupId: 998877,
        userIds: [665544],
        groupContact: 'onebot11:group:998877',
        userContacts: ['onebot11:private:665544'],
      },
    ])
  })

  /**
   * 群普通管理员卡片先暂存到页面态，只有右上角保存才会触发后端写入。
   */
  it('stages group admin pairs from the card save button before the page save writes them', async () => {
    const batchBodies: Array<Record<string, Record<string, unknown>>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init, {
        onBatchBody: (body) => batchBodies.push(body),
      })
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: 'bili-token', fields: []})}
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token',
            fields: [
              {key: 'admins', label: 'admins', value: '[]', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')
    await user.click(await screen.findByRole('button', {name: '管理员', pressed: false}))

    await user.type(await screen.findByLabelText('群聊'), '123456')
    await user.type(screen.getByLabelText('个人QQ号'), '654321')
    await user.click(screen.getByRole('button', {name: '添加一行'}))
    const groupInputs = screen.getAllByLabelText('群聊')
    const userInputs = screen.getAllByLabelText('个人QQ号')
    await user.type(groupInputs[1], '111111')
    await user.type(userInputs[1], '222222')
    const stageButton = screen.getByRole('button', {name: '暂存'})
    expect(screen.queryByText('暂无群普通管理员')).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', {name: '删除'})).toHaveLength(2)

    await user.click(stageButton)
    expect(screen.getAllByLabelText('群聊')[0]).toHaveValue('123456')
    expect(screen.getAllByLabelText('个人QQ号')[0]).toHaveValue('654321')
    expect(screen.getAllByLabelText('群聊')[1]).toHaveValue('111111')
    expect(screen.getAllByLabelText('个人QQ号')[1]).toHaveValue('222222')
    expect(batchBodies).toHaveLength(0)

    await user.click(screen.getByRole('button', {name: '保存'}))
    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    await waitFor(() => expect(batchBodies).toHaveLength(1))
    const botPost = batchBodies[0].botConfig
    expect(botPost?.admins).toEqual([
      {
        groupId: 123456,
        userIds: [654321],
        groupContact: 'onebot11:group:123456',
        userContacts: ['onebot11:private:654321'],
      },
      {
        groupId: 111111,
        userIds: [222222],
        groupContact: 'onebot11:group:111111',
        userContacts: ['onebot11:private:222222'],
      },
    ])
  })

  /**
   * 管理员页连续保存时必须沿用后端返回的新快照令牌，避免第二次保存被并发保护误判为旧快照。
   */
  it('uses the latest bot snapshot token when saving group admins repeatedly', async () => {
    const {payloads: botPostBodies, handleBatchBody} = collectBotBatchPayloads()
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init, {
        botSnapshotToken: `bot-token-${botPostBodies.length + 1}`,
        onBatchBody: handleBatchBody,
      })
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: '', fields: []})}
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token-1',
            fields: [
              {key: 'admins', label: 'admins', value: '[]', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')
    await user.click(await screen.findByRole('button', {name: '管理员', pressed: false}))

    await user.type(await screen.findByLabelText('群聊'), '123456')
    await user.type(screen.getByLabelText('个人QQ号'), '654321')
    await user.click(screen.getByRole('button', {name: '暂存'}))
    await user.click(screen.getByRole('button', {name: '保存'}))
    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    await waitFor(() => expect(botPostBodies).toHaveLength(1))
    await waitFor(() => expect(screen.getByRole('button', {name: '保存'})).toBeEnabled())

    await user.clear(await screen.findByLabelText('群聊'))
    await user.clear(screen.getByLabelText('个人QQ号'))
    await user.type(await screen.findByLabelText('群聊'), '222222')
    await user.type(screen.getByLabelText('个人QQ号'), '333333')
    await user.click(screen.getByRole('button', {name: '暂存'}))
    await user.click(screen.getByRole('button', {name: '保存'}))
    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    await waitFor(() => expect(botPostBodies).toHaveLength(2))

    expect(botPostBodies[0].snapshotToken).toBe('bot-token-1')
    expect(botPostBodies[1].snapshotToken).toBe('bot-token-2')
  })

  /**
   * 保存平台连接参数时不应提交 admins 投影，避免把 custom subject 管理员配置重建为普通 OneBot11 数字项。
   */
  it('does not submit admins when saving onebot connection settings', async () => {
    let botPostBody: Record<string, unknown> | null = null
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init, {
        onBatchBody: (body) => {
          botPostBody = body.botConfig || null
        },
      })
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: 'bili-token', fields: []})}
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token-1',
            fields: [
              {key: 'platform.type', label: '平台类型', value: 'onebot11', capability: 'EDITABLE', editable: true},
              {key: 'platform.adapter', label: '适配器', value: 'onebot11', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.host', label: 'OneBot11 主机', value: '127.0.0.1', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.port', label: 'OneBot11 端口', value: '3001', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.token', label: 'OneBot11 Token', value: '******', capability: 'MASKED', editable: true},
              {key: 'platform.onebot11.useTls', label: '启用 TLS', value: 'false', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.heartbeatInterval', label: '心跳间隔', value: '30000', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.reconnectInterval', label: '重连间隔', value: '5000', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.sendMode', label: '图片发送方式', value: 'base64', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.maxReconnectAttempts', label: '最大重连次数', value: '-1', capability: 'EDITABLE', editable: true},
              {key: 'platform.onebot11.connectTimeout', label: '连接超时', value: '10000', capability: 'EDITABLE', editable: true},
              {key: 'webui.enabled', label: '启用 WebUI', value: 'false', capability: 'EDITABLE', editable: true},
              {
                key: 'admins',
                label: 'admins',
                value: JSON.stringify([{groupId: 0, userIds: [], groupContact: 'custom:room:alpha', userContacts: ['custom:user:beta']}]),
                capability: 'EDITABLE',
                editable: true,
              },
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')
    await user.clear(await screen.findByLabelText('OneBot11 主机'))
    await user.type(screen.getByLabelText('OneBot11 主机'), '10.0.0.2')
    await user.click(screen.getByRole('button', {name: '保存'}))
    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await waitFor(() => expect(botPostBody).not.toBeNull())
    expect(botPostBody).not.toHaveProperty('admins')
  })

  /**
   * 只修改群普通管理员时不应写入 BiliConfig，避免无关旧配置校验失败挡住 bot.yml 保存。
   */
  it('does not save BiliConfig when only group admins changed', async () => {
    const batchBodies: Array<Record<string, Record<string, unknown>>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const settingsResponse = createSettingsResponse(url, init, {
        onBatchBody: (body) => batchBodies.push(body),
      })
      if (settingsResponse) {
        return settingsResponse
      }
      if (url.includes('/api/config/bili-config')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'BiliConfig.yml',
            snapshotToken: 'bili-token',
            fields: [
              {key: 'adminContact', label: 'adminContact', value: 'onebot11:private:42', capability: 'EDITABLE', editable: true},
              {key: 'admin', label: 'admin', value: '42', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      if (url.includes('/api/config/bot')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            sourceFile: 'bot.yml',
            snapshotToken: 'bot-token-1',
            fields: [
              {key: 'admins', label: 'admins', value: '[]', capability: 'EDITABLE', editable: true},
            ],
          }),
        }
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#settings')
    await user.click(await screen.findByRole('button', {name: '管理员', pressed: false}))

    await user.type(await screen.findByLabelText('群聊'), '123456')
    await user.type(screen.getByLabelText('个人QQ号'), '654321')
    await user.click(screen.getByRole('button', {name: '暂存'}))
    await user.click(screen.getByRole('button', {name: '保存'}))
    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await waitFor(() => expect(batchBodies).toHaveLength(1))
    expect(batchBodies[0].botConfig).toBeDefined()
    expect(batchBodies[0].biliConfig).toBeUndefined()
  })

  /**
   * 首页布局需要恢复旧 WebUI 的运行信息密度，核心指标标签不能被折叠掉。
   */
  it('renders the dashboard runtime metric labels required for parity', async () => {
    renderAtPath('/')

    await waitFor(() => expect(screen.getByText('版本')).toBeInTheDocument())
    expect(screen.getByText('启动时间')).toBeInTheDocument()
    expect(screen.getByText('运行时长')).toBeInTheDocument()
    expect(screen.getByText('系统负载')).toBeInTheDocument()
    expect(screen.getByText('CPU')).toBeInTheDocument()
    expect(screen.getByText('内存')).toBeInTheDocument()
    expect(screen.getByText('存储')).toBeInTheDocument()
    expect(screen.queryByText('Docker')).not.toBeInTheDocument()
    expect(screen.getByText('今日推送')).toBeInTheDocument()
  })

  it('renders the login screen for the login path', () => {
    renderAtPath('/login')

    expect(screen.getByRole('heading', {name: '登录'})).toBeInTheDocument()
    expect(screen.getByLabelText('WebUI 密码')).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '管理员'})).not.toBeInTheDocument()
  })

  /**
   * 登录页必须沿用 cookie 里的主题模式，并把认证失败翻成普通提示。
   */
  it('renders the login page in the cookie theme and shows a friendly auth error', async () => {
    window.localStorage.removeItem('hoshimi_cat_bot_webui_theme')
    document.cookie = 'hoshimi_cat_bot_webui_theme=dark; path=/'
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/auth/login')) {
        return {ok: false, status: 401, json: async () => ({message: 'bad password'})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/login')

    expect(document.documentElement.classList.contains('theme-dark')).toBe(true)
    await user.type(screen.getByLabelText('WebUI 密码'), 'bad-password')
    await user.click(screen.getByRole('button', {name: '登录'}))

    expect(await screen.findByText('密码错误，请重试')).toBeInTheDocument()
    expect(screen.queryByText('HTTP 401')).not.toBeInTheDocument()
    document.cookie = 'hoshimi_cat_bot_webui_theme=; Max-Age=0; path=/'
    window.localStorage.removeItem('hoshimi_cat_bot_webui_theme')
    document.documentElement.classList.remove('theme-system', 'theme-light', 'theme-dark')
    delete document.documentElement.dataset.theme
  })

  /**
   * 日志页需要提供旧 WebUI 等价的来源、过滤、搜索、自动刷新、导出和清空控件。
   */
  it('renders log filtering, auto-refresh, export, and clear controls', async () => {
    // 日志窗口高度由浏览器计算，测试中固定 scrollHeight 以验证加载后滚动到底部。
    Object.defineProperty(HTMLElement.prototype, 'scrollHeight', {
      configurable: true,
      get() {
        return this instanceof HTMLPreElement ? 640 : 0
      },
    })
    renderAtPath('/#logs')

    expect(screen.queryByText('来源数量')).not.toBeInTheDocument()
    expect(screen.queryByText('当前来源')).not.toBeInTheDocument()
    expect(screen.queryByText('日志行数')).not.toBeInTheDocument()
    expect((await screen.findAllByRole('heading', {name: '实时日志'})).length).toBeGreaterThan(0)
    expect(screen.getByLabelText('日志来源')).toBeInTheDocument()
    expect(screen.getByLabelText('级别')).toBeInTheDocument()
    expect(screen.getByLabelText('模块')).toBeInTheDocument()
    expect(screen.getByLabelText('搜索')).toBeInTheDocument()
    expect(screen.getByLabelText('自动刷新')).toBeInTheDocument()
    expect(screen.getByLabelText('自动刷新')).toHaveClass('toggle-input')
    expect(screen.getByLabelText('自动刷新').closest('[data-toggle-shell]')).not.toBeNull()
    expect(screen.getByRole('button', {name: '刷新'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '导出'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '清空'})).toBeInTheDocument()
    expect(screen.getByLabelText('日志来源')).toHaveClass('w-full')
    expect(screen.getByLabelText('模块')).toHaveClass('w-full')
    expect(screen.getByLabelText('搜索')).toHaveClass('w-full')
    expect(screen.getByLabelText('搜索').parentElement).toHaveClass('min-w-0')
    const logWindow = document.querySelector('pre')
    expect(logWindow).not.toBeNull()
    expect(logWindow).toHaveClass('log-container')
    expect(logWindow?.parentElement?.querySelector('[data-log-filter-bar]')).not.toBeNull()
    await waitFor(() => expect((logWindow as HTMLPreElement).scrollTop).toBe(640))
  })

  /**
   * 订阅页需要恢复旧 WebUI 的列表筛选、新增模式和嵌套编辑器入口。
   */
  it('renders subscription filters, create modes, and nested editor controls', async () => {
    const themePostBodies: string[] = []
    const filterPostBodies: string[] = []
    const templatePostBodies: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [{
              id: 'sub-1',
              kind: 'dynamic',
              title: '测试订阅',
              identifierLabel: 'UID: 123',
              sourceId: 123,
              tags: ['动态'],
              targetSectionTitle: '推送目标',
              targets: ['onebot11:group:1072150397', 'onebot11:group:1245551'],
              filterInfo: 'group:1072150397 类型黑名单: 空，正则黑名单: 测试',
              filterCount: 2,
              templateNames: ['默认模板', '备用模板'],
              templateCount: 2,
              atAllInfo: '全部动态',
              themeColor: '#33aaff',
              themeColorCount: 1,
              lastUpdatedEpochMillis: 1_700_000_000_000,
            }],
          }),
        }
      }
      if (url.endsWith('/api/subscriptions/sub-1/filters') && init?.method === 'POST') {
        filterPostBodies.push(String(init.body || ''))
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/filters')) {
        return {ok: true, status: 200, json: async () => ({filters: [{key: 'filter-1', kind: 'regex', mode: 'black', content: '广告', label: '正则', prefix: '黑名单'}]})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/templates') && init?.method === 'POST') {
        templatePostBodies.push(String(init.body || ''))
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/templates')) {
        return {ok: true, status: 200, json: async () => ({templates: [{key: 'template-1', type: 'dynamic', typeLabel: '动态', name: '默认模板', content: '{{title}}'}], randomEnabled: true})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/atall')) {
        return {ok: true, status: 200, json: async () => ({items: [{key: '全部动态', type: '全部动态', summary: '全部动态：10001', groups: ['10001']}]})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/theme') && init?.method === 'POST') {
        themePostBodies.push(String(init.body || ''))
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/theme')) {
        return {ok: true, status: 200, json: async () => ({color: '#33aaff'})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#subscriptions')

    expect(await screen.findByText('测试订阅')).toBeInTheDocument()
    expect(screen.queryByText('全部订阅')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '全部'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '动态'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '番剧'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '分组'})).not.toBeInTheDocument()
    expect(screen.queryByLabelText('搜索订阅')).not.toBeInTheDocument()
    expect(screen.getByText('过滤器信息')).toBeInTheDocument()
    expect(screen.getByText('模板信息')).toBeInTheDocument()
    expect(screen.getByText('at全体')).toBeInTheDocument()
    expect(screen.getByText('主题色')).toBeInTheDocument()
    expect(screen.getByText('群聊：1072150397、1245551')).toBeInTheDocument()
    expect(screen.getByText('2 个过滤器')).toBeInTheDocument()
    expect(screen.getByText('2 个模板')).toBeInTheDocument()
    expect(screen.queryByText('onebot11:group:1072150397')).not.toBeInTheDocument()
    expect(screen.queryByText('group:1072150397 类型黑名单: 空，正则黑名单: 测试')).not.toBeInTheDocument()
    expect(screen.queryByText('默认模板')).not.toBeInTheDocument()
    expect(screen.getByRole('button', {name: '新增订阅'}).parentElement?.parentElement).toHaveClass('justify-between')

    await user.click(screen.getByRole('button', {name: '新增订阅'}))
    expect(screen.getByRole('dialog', {name: '新增订阅'})).toBeInTheDocument()
    expect(screen.getByRole('dialog', {name: '新增订阅'})).toHaveClass('modal-panel')
    expect(screen.getByRole('dialog', {name: '新增订阅'}).parentElement).toHaveClass('modal-overlay')
    expect(screen.queryByRole('button', {name: '关闭新增订阅'})).not.toBeInTheDocument()
    fireEvent.mouseDown(document.querySelector('div[role="presentation"]') as Element)
    expect(screen.getByRole('dialog', {name: '新增订阅'})).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('订阅类型'), 'group')
    expect(screen.getByLabelText('分组名称')).toBeInTheDocument()
    expect(screen.getByLabelText('分组 UID')).toBeInTheDocument()
    expect(screen.getByLabelText('分组目标群')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '取消'}))

    await user.click(screen.getByRole('button', {name: '编辑'}))
    expect(screen.getByRole('dialog', {name: '编辑订阅配置'})).toBeInTheDocument()
    expect(screen.getByRole('dialog', {name: '编辑订阅配置'})).toHaveClass('modal-panel')
    const editorOverlay = document.querySelector('[data-subscription-editor-overlay]')
    const editorPanel = document.querySelector('[data-subscription-editor-panel]')
    expect(editorOverlay).not.toBeNull()
    expect(editorOverlay).toHaveClass('inset-0')
    expect(editorOverlay).toHaveClass('modal-overlay')
    expect(editorOverlay).not.toHaveClass('lg:left-60')
    expect(editorPanel).not.toBeNull()
    const editorDialog = screen.getByRole('dialog', {name: '编辑订阅配置'})
    expect(editorDialog).not.toHaveClass('lg:grid-rows-[auto_minmax(0,1fr)]')
    expect(editorPanel).not.toHaveClass('lg:row-start-2')
    expect(editorPanel).toHaveClass('lg:max-w-lg')
    expect(screen.getByRole('button', {name: '编辑推送群聊'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑过滤器'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑模板'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑at全体'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑主题色'})).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '编辑过滤器'}))
    const filterRow = (await screen.findByText('广告')).closest('div')
    expect(filterRow).not.toBeNull()
    expect(within(filterRow as HTMLElement).getByRole('button', {name: '编辑'})).toBeInTheDocument()
    expect(within(filterRow as HTMLElement).getByRole('button', {name: '删除'})).toBeInTheDocument()
    await user.click(within(filterRow as HTMLElement).getByRole('button', {name: '编辑'}))
    expect(screen.getByText('filter-1 正则过滤')).toBeInTheDocument()
    expect(screen.queryByText('广告')).not.toBeInTheDocument()
    expect(screen.getByLabelText('规则内容')).toHaveValue('广告')
    expect(screen.getByRole('button', {name: '取消'})).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '取消'}))
    expect(screen.getByRole('button', {name: '添加过滤器'})).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '添加过滤器'}))
    expect(screen.getByRole('button', {name: '取消'})).toBeInTheDocument()
    expect(screen.queryByText('暂无过滤器')).not.toBeInTheDocument()
    expect(screen.queryByText('广告')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '保存过滤器'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('规则内容必须填写')
    await user.type(screen.getByLabelText('规则内容'), '广告')
    await user.click(screen.getByRole('button', {name: '保存过滤器'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('目标群聊必须至少选择一个')
    await user.click(screen.getByLabelText('onebot11:group:1072150397'))
    await user.click(screen.getByRole('button', {name: '保存过滤器'}))
    await user.type(await screen.findByLabelText('确认密码'), 'filter-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    expect(await screen.findByText('过滤器已保存')).toBeInTheDocument()
    expect(JSON.parse(filterPostBodies.at(-1) || '{}')).toMatchObject({
      content: '广告',
      targetGroups: ['onebot11:group:1072150397'],
      confirmationPassword: 'filter-password',
    })

    await user.click(screen.getByRole('button', {name: '编辑模板'}))
    expect(await screen.findByLabelText('随机模板')).toBeChecked()
    expect(screen.getByRole('button', {name: '添加模板'})).toBeInTheDocument()
    const templateRow = (await screen.findByText('默认模板')).closest('div')
    expect(templateRow).not.toBeNull()
    await user.click(within(templateRow as HTMLElement).getByRole('button', {name: '编辑'}))
    expect(screen.getByText('默认模板')).toBeInTheDocument()
    expect(screen.queryByLabelText('随机模板')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '添加模板'})).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '取消'}))
    await user.click(screen.getByRole('button', {name: '添加模板'}))
    expect(screen.getByRole('button', {name: '取消'})).toBeInTheDocument()
    expect(screen.queryByText('暂无模板')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('随机模板')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('模板类型'), 'dynamic')
    await user.click(screen.getByRole('button', {name: '保存模板'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('模板名称和模板内容必须填写')
    await user.type(screen.getByLabelText('模板名称'), '默认模板')
    fireEvent.change(screen.getByLabelText('模板内容'), {target: {value: '{{title}}'}})
    await user.click(screen.getByRole('button', {name: '保存模板'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('目标群聊必须至少选择一个')
    await user.click(screen.getByLabelText('onebot11:group:1072150397'))
    await user.click(screen.getByRole('button', {name: '保存模板'}))
    await user.type(await screen.findByLabelText('确认密码'), 'template-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    expect(await screen.findByText('模板已保存')).toBeInTheDocument()
    expect(JSON.parse(templatePostBodies.at(-1) || '{}')).toMatchObject({
      name: '默认模板',
      content: '{{title}}',
      targetGroups: ['onebot11:group:1072150397'],
      confirmationPassword: 'template-password',
    })

    await user.click(screen.getByRole('button', {name: '编辑at全体'}))
    expect(await screen.findByRole('button', {name: '添加at全体'})).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '添加at全体'}))
    expect(screen.getByRole('button', {name: '取消'})).toBeInTheDocument()
    await user.click(screen.getByLabelText('onebot11:group:1072150397'))
    expect(screen.getByLabelText('onebot11:group:1072150397')).toBeInTheDocument()
    expect(screen.getByLabelText('onebot11:group:1245551')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '保存at全体'}))
    await user.type(await screen.findByLabelText('确认密码'), 'atall-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    expect(await screen.findByText('@全体已保存')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '编辑主题色'}))
    expect(await screen.findByLabelText('主题颜色')).toHaveValue('#33aaff')
    expect(screen.getByLabelText('onebot11:group:1072150397')).toBeChecked()
    expect(screen.getByLabelText('onebot11:group:1245551')).toBeChecked()
    await user.clear(screen.getByLabelText('主题颜色'))
    await user.type(screen.getByLabelText('主题颜色'), 'not-a-color')
    await user.click(screen.getByRole('button', {name: '保存主题色'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('主题颜色必须是 HEX 颜色')
    await user.clear(screen.getByLabelText('主题颜色'))
    await user.type(screen.getByLabelText('主题颜色'), '#33aaff')
    await user.click(screen.getByLabelText('onebot11:group:1245551'))
    await user.click(screen.getByRole('button', {name: '保存主题色'}))
    await user.type(await screen.findByLabelText('确认密码'), 'theme-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    expect(await screen.findByText('主题色已保存')).toBeInTheDocument()
    expect(JSON.parse(themePostBodies.at(-1) || '{}')).toMatchObject({
      color: '#33aaff',
      targetGroups: ['onebot11:group:1072150397'],
      confirmationPassword: 'theme-password',
    })
  }, 10_000)

  /**
   * 番剧后端只支持订阅本身和主题色，前端不能展示无效的过滤器、模板和 @全体入口。
   */
  it('hides unsupported nested editors for bangumi subscriptions', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [{
              id: 'bangumi:12345',
              kind: 'bangumi',
              title: '测试番剧',
              identifierLabel: 'SS: 12345 / MD: 67890',
              sourceId: 12345,
              tags: ['番剧'],
              targetSectionTitle: '推送目标',
              targets: ['onebot11:group:1072150397'],
              filterInfo: '不适用',
              filterCount: 0,
              templateNames: [],
              templateCount: 0,
              atAllInfo: '未开启',
              themeColor: '#33aaff',
              themeColorCount: 1,
              lastUpdatedEpochMillis: 1_700_000_000_000,
            }],
          }),
        }
      }
      if (url.endsWith('/api/subscriptions/bangumi%3A12345/theme')) {
        return {ok: true, status: 200, json: async () => ({color: '#33aaff'})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#subscriptions')

    expect(await screen.findByText('测试番剧')).toBeInTheDocument()
    expect(screen.queryByText('过滤器信息')).not.toBeInTheDocument()
    expect(screen.queryByText('模板信息')).not.toBeInTheDocument()
    expect(screen.queryByText('at全体')).not.toBeInTheDocument()
    expect(screen.getByText('主题色')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '编辑'}))
    expect(screen.queryByRole('button', {name: '编辑过滤器'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '编辑模板'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '编辑at全体'})).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '编辑主题色'}))
    expect(await screen.findByLabelText('主题颜色')).toHaveValue('#33aaff')
  })

  /**
   * 推送群聊编辑器位于过滤器上方，新增输入必须是正整数并携带确认密码写入后端。
   */
  it('edits subscription target groups from the nested editor', async () => {
    const targetPostBodies: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [{
              id: 'sub-1',
              kind: 'dynamic',
              title: '测试订阅',
              sourceId: 1,
              targets: ['onebot11:group:1072150397'],
              tags: ['动态'],
              filterCount: 0,
              templateCount: 0,
            }],
          }),
        }
      }
      if (url.endsWith('/api/subscriptions/sub-1/targets') && init?.method === 'POST') {
        targetPostBodies.push(String(init.body || ''))
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/targets')) {
        return {ok: true, status: 200, json: async () => ({items: [{key: 'onebot11:group:1072150397', targetGroup: '1072150397', summary: '群聊：1072150397'}]})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#subscriptions')

    expect(await screen.findByText('测试订阅')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '编辑'}))
    const editorDialog = screen.getByRole('dialog', {name: '编辑订阅配置'})
    await user.click(screen.getByRole('button', {name: '编辑推送群聊'}))
    expect(await within(editorDialog).findByText('群聊：1072150397')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '新增推送群聊'}))
    await user.type(screen.getByLabelText('推送群聊'), 'abc')
    await user.click(screen.getByRole('button', {name: '保存推送群聊'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('推送群聊必须是正整数')

    await user.clear(screen.getByLabelText('推送群聊'))
    await user.type(screen.getByLabelText('推送群聊'), '10001')
    await user.click(screen.getByRole('button', {name: '保存推送群聊'}))
    await user.type(await screen.findByLabelText('确认密码'), 'target-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    expect(await screen.findByText('推送群聊已保存')).toBeInTheDocument()
    expect(JSON.parse(targetPostBodies.at(-1) || '{}')).toMatchObject({
      targetGroup: '10001',
      confirmationPassword: 'target-password',
    })
  })

  /**
   * 新增订阅在打开高风险确认前必须先校验当前类型的必填项和数字格式。
   */
  it('validates new subscription input before opening the high-risk password dialog', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions') && (!init || init.method === 'GET')) {
        return {ok: true, status: 200, json: async () => ({items: []})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    renderAtPath('/#subscriptions')

    await user.click(await screen.findByRole('button', {name: '新增订阅'}))
    await user.type(screen.getByLabelText('UID'), 'abc')
    await user.type(screen.getByLabelText('目标群聊'), '10001')
    await user.click(screen.getByRole('button', {name: '确认新增'}))

    expect(await screen.findByRole('alert')).toHaveTextContent('UID必须是正整数')
    expect(screen.queryByRole('dialog', {name: '密码确认'})).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/api/subscriptions') && init?.method === 'POST')).toBe(false)
  })

  /**
   * 新增订阅成功后只通过全局 toast 反馈，避免页面局部散落弱提示。
   */
  it('shows subscription submission feedback through the toast system', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions') && (!init || init.method === 'GET')) {
        return {ok: true, status: 200, json: async () => ({items: []})}
      }
      if (url.endsWith('/api/subscriptions') && init?.method === 'POST') {
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#subscriptions')

    await user.click(await screen.findByRole('button', {name: '新增订阅'}))
    await user.type(screen.getByLabelText('UID'), '12345')
    await user.type(screen.getByLabelText('目标群聊'), '10001')
    await user.click(screen.getByRole('button', {name: '确认新增'}))
    await user.type(await screen.findByLabelText('确认密码'), 'subscription-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    const toast = (await screen.findByText('订阅已提交')).closest('[data-toast]')
    expect(toast).not.toBeNull()
    expect(toast).toHaveClass('toast-success')
    expect(toast?.parentElement).toHaveAttribute('data-toast-viewport', 'true')
  })

  /**
   * 分组卡片额外展示订阅 ID 编辑器，新增项支持 UID 正整数和 ss/md/ep 番剧标识。
   */
  it('shows the group uid editor only for group subscriptions', async () => {
    const uidPostBodies: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [{
              id: 'group:team-a',
              kind: 'group',
              title: 'team-a',
              sourceId: 0,
              targets: ['onebot11:group:1072150397'],
              tags: ['分组'],
              filterCount: 0,
              templateCount: 0,
            }],
          }),
        }
      }
      if (url.endsWith('/api/subscriptions/group%3Ateam-a/uids') && init?.method === 'POST') {
        uidPostBodies.push(String(init.body || ''))
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      if (url.endsWith('/api/subscriptions/group%3Ateam-a/uids')) {
        return {ok: true, status: 200, json: async () => ({items: [{key: '12345', uid: 12345, summary: 'UID：12345'}, {key: 'md12345', identifier: 'md12345', summary: '番剧：测试番剧（md12345）'}]})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#subscriptions')

    expect(await screen.findByText('team-a')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '编辑'}))
    const editorDialog = screen.getByRole('dialog', {name: '编辑订阅配置'})
    expect(screen.getByRole('button', {name: '编辑订阅ID'})).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '编辑订阅ID'}))
    expect(await within(editorDialog).findByText('UID：12345')).toBeInTheDocument()
    expect(await within(editorDialog).findByText('番剧：测试番剧（md12345）')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '新增订阅ID'}))
    await user.type(screen.getByLabelText('订阅ID'), 'bad')
    await user.click(screen.getByRole('button', {name: '保存订阅ID'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('订阅ID必须是 UID 正整数，或 ss/md/ep 前缀番剧ID')

    await user.clear(screen.getByLabelText('订阅ID'))
    await user.type(screen.getByLabelText('订阅ID'), 'MD12345')
    await user.click(screen.getByRole('button', {name: '保存订阅ID'}))
    await user.type(await screen.findByLabelText('确认密码'), 'uid-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    expect(await screen.findByText('订阅ID已保存')).toBeInTheDocument()
    expect(JSON.parse(uidPostBodies.at(-1) || '{}')).toMatchObject({
      uid: 'md12345',
      confirmationPassword: 'uid-password',
    })
  })

  /**
   * 动态类型过滤器的规则内容必须使用固定选项，避免用户输入后端不接受的标签值。
   */
  it('uses dynamic type options when editing subscription type filters', async () => {
    const filterPostBodies: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [{
              id: 'sub-1',
              kind: 'dynamic',
              title: '测试订阅',
              sourceId: 1,
              targets: ['onebot11:group:1072150397'],
              tags: ['动态'],
              filterCount: 0,
              templateCount: 0,
            }],
          }),
        }
      }
      if (url.endsWith('/api/subscriptions/sub-1/filters') && init?.method === 'POST') {
        filterPostBodies.push(String(init.body || ''))
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/filters')) {
        return {ok: true, status: 200, json: async () => ({filters: []})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))
    const user = userEvent.setup()

    renderAtPath('/#subscriptions')

    expect(await screen.findByText('测试订阅')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '编辑'}))
    await user.click(screen.getByRole('button', {name: '编辑过滤器'}))
    await user.click(await screen.findByRole('button', {name: '添加过滤器'}))
    await user.selectOptions(screen.getByLabelText('过滤类型'), 'type')
    expect(screen.getByLabelText('规则内容').tagName).toBe('SELECT')
    expect(screen.getByRole('option', {name: '动态'})).toBeInTheDocument()
    expect(screen.getByRole('option', {name: '转发动态'})).toBeInTheDocument()
    expect(screen.getByRole('option', {name: '视频'})).toBeInTheDocument()
    expect(screen.getByRole('option', {name: '音乐'})).toBeInTheDocument()
    expect(screen.getByRole('option', {name: '专栏'})).toBeInTheDocument()
    expect(screen.getByRole('option', {name: '直播'})).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('规则内容'), '视频')
    await user.click(screen.getByRole('button', {name: '保存过滤器'}))
    expect(screen.getByRole('alert')).toHaveTextContent('目标群聊必须至少选择一个')
    await user.click(screen.getByLabelText('onebot11:group:1072150397'))
    await user.click(screen.getByRole('button', {name: '保存过滤器'}))
    await user.type(await screen.findByLabelText('确认密码'), 'filter-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await waitFor(() => expect(filterPostBodies).toHaveLength(1))
    expect(JSON.parse(filterPostBodies[0])).toMatchObject({
      kind: 'type',
      mode: 'black',
      content: '视频',
      targetGroups: ['onebot11:group:1072150397'],
      confirmationPassword: 'filter-password',
    })
  })

  /**
   * 编辑器切换订阅时必须清空旧面板，并忽略前一个订阅尚未完成的异步加载。
   */
  it('resets subscription editor state and ignores stale nested config loads when the item changes', async () => {
    const sub1Filters = createDeferred<{filters: Record<string, unknown>[]}>()
    const sub2Filters = createDeferred<{filters: Record<string, unknown>[]}>()
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [
              {id: 'sub-1', title: '订阅1', sourceId: 1, targets: [], tags: [], filterCount: 1, templateCount: 0},
              {id: 'sub-2', title: '订阅2', sourceId: 2, targets: [], tags: [], filterCount: 1, templateCount: 0},
            ],
          }),
        }
      }
      if (url.endsWith('/api/subscriptions/sub-1/filters')) {
        return sub1Filters.promise.then((payload) => ({ok: true, status: 200, json: async () => payload}))
      }
      if (url.endsWith('/api/subscriptions/sub-2/filters')) {
        return sub2Filters.promise.then((payload) => ({ok: true, status: 200, json: async () => payload}))
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#subscriptions')

    expect(await screen.findByText('订阅1')).toBeInTheDocument()
    const cardEditButtons = screen.getAllByRole('button', {name: '编辑'})
    await user.click(cardEditButtons[0])
    await user.click(screen.getByRole('button', {name: '编辑过滤器'}))
    await user.click(cardEditButtons[1])
    expect(screen.getByRole('dialog', {name: '编辑订阅配置'})).toHaveTextContent('订阅2')

    await act(async () => {
      sub1Filters.resolve({filters: [{key: 'filter-1', content: '订阅1过滤器'}]})
      await sub1Filters.promise
    })
    expect(screen.queryByText('订阅1过滤器')).not.toBeInTheDocument()
    expect(screen.getByText('选择左侧编辑器开始配置')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '编辑过滤器'}))
    await act(async () => {
      sub2Filters.resolve({filters: [{key: 'filter-2', content: '订阅2过滤器'}]})
      await sub2Filters.promise
    })
    expect(await screen.findByText('订阅2过滤器')).toBeInTheDocument()
    expect(screen.queryByText('订阅1过滤器')).not.toBeInTheDocument()
  })

  /**
   * Shell 侧边栏需要保留管理员操作、主题偏好和可键盘关闭的账号弹窗。
   */
  it('supports admin menu actions, theme preference, and Escape modal close', async () => {
    const user = userEvent.setup()

    renderAtPath('/')

    const aside = document.querySelector('aside')
    expect(aside).not.toBeNull()
    expect(aside).toHaveClass('fixed')
    expect(aside).toHaveClass('inset-y-0')
    expect(aside).toHaveClass('left-0')
    expect(aside?.nextElementSibling).toHaveClass('lg:col-start-2')
    expect(within(aside as HTMLElement).getByLabelText('主题模式')).toBeInTheDocument()
    expect(within(aside as HTMLElement).getByRole('button', {name: '管理员', expanded: false})).toBeInTheDocument()
    expect(within(aside as HTMLElement).getByLabelText('主题模式').parentElement).toHaveClass('space-y-1')
    expect(within(aside as HTMLElement).getByLabelText('主题模式').parentElement?.parentElement?.parentElement).toHaveClass('lg:-ml-5')
    expect(within(aside as HTMLElement).getByLabelText('主题模式').parentElement?.parentElement?.parentElement).toHaveClass('lg:mr-[-21px]')
    expect(within(aside as HTMLElement).getByRole('button', {name: '管理员', expanded: false})).toHaveClass('w-full')
    expect(within(aside as HTMLElement).getByRole('button', {name: '管理员', expanded: false})).toHaveClass('justify-start')
    expect(document.querySelector('header')).toBeNull()

    // 侧边栏顶部和底部都需要常驻视口左侧，避免随主页面滚动。
    expect(readFileSync('src/components/Shell.tsx', 'utf8')).toContain('fixed inset-y-0 left-0')

    await user.selectOptions(screen.getByLabelText('主题模式'), 'dark')
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true)
    expect(screen.getByRole('option', {name: '亮色'})).toBeInTheDocument()
    expect(screen.getByRole('option', {name: '暗色'})).toBeInTheDocument()
    expect(readFileSync('src/styles.css', 'utf8')).toContain('.theme-dark')
    expect(readFileSync('src/styles.css', 'utf8')).toContain('.theme-dark .border-l-emerald-500')
    expect(readFileSync('src/styles.css', 'utf8')).toContain('.subscription-info-block')
    await user.selectOptions(screen.getByLabelText('主题模式'), 'system')
    expect(document.documentElement.classList.contains('theme-system')).toBe(true)
    expect(readFileSync('src/styles.css', 'utf8')).toContain('.theme-system .bg-slate-50')

    await user.click(screen.getByRole('button', {name: '管理员', expanded: false}))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.mouseDown(document.body)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '管理员', expanded: false}))
    await user.click(screen.getByRole('button', {name: '修改密码'}))
    expect(screen.getByRole('dialog', {name: '修改密码'})).toBeInTheDocument()

    const overlay = document.querySelector('div[role="presentation"]')
    expect(overlay).not.toBeNull()
    fireEvent.mouseDown(overlay as Element)
    expect(screen.queryByRole('dialog', {name: '修改密码'})).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '管理员', expanded: false}))
    await user.click(screen.getByRole('button', {name: '修改密码'}))
    expect(screen.getByRole('dialog', {name: '修改密码'})).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', {name: '修改密码'})).not.toBeInTheDocument()
  })

  /**
   * 订阅删除只能使用明确的订阅主键，不能在缺少主键时继续调用删除接口。
   */
  it('does not delete subscriptions when the item lacks a stable deletion id', async () => {
    const deleteCalls: Array<[string, RequestInit | undefined]> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions') && (!init || init.method === 'GET')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            items: [{
              uid: 'uid-only',
              title: '无主键订阅',
              identifierLabel: 'UID: 123',
              sourceId: 123,
              targets: [],
              filterInfo: '',
              templateNames: [],
              atAllInfo: '',
              themeColor: '',
            }],
          }),
        }
      }
      if (url.endsWith('/api/subscriptions') && init?.method === 'DELETE') {
        deleteCalls.push([url, init])
        return {ok: true, status: 200, json: async () => ({success: true})}
      }
      return {ok: true, status: 200, json: async () => ({success: true})}
    }))

    const user = userEvent.setup()
    renderAtPath('/#subscriptions')

    expect(await screen.findByText('无主键订阅')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '删除'}))

    expect(screen.getByText('当前条目缺少可删除标识')).toBeInTheDocument()
    expect(deleteCalls).toHaveLength(0)
  })

  /**
   * React 源码不得重新引入浏览器原生确认、提示或通知 API。
   */
  it('does not use native browser dialog APIs in React source', () => {
    const sourceFiles = [
      'src/App.tsx',
      'src/components/Shell.tsx',
      'src/contexts/ConfirmationContext.tsx',
      'src/pages/SubscriptionsPage.tsx',
      'src/pages/SettingsPage.tsx',
      'src/pages/LogsPage.tsx',
    ]
    const source = sourceFiles.map((file) => readFileSync(file, 'utf8')).join('\n')

    expect(source).not.toMatch(/window\.confirm|window\.prompt|window\.alert|new\s+Notification|Notification\./)
  })
})
