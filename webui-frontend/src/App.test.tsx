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
      if (url.includes('/api/config/bili-config') && init?.method === 'POST') {
        return {ok: true, status: 200, json: async () => ({success: true, message: 'bili 已保存'})}
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
    expect(screen.getByText(/保存成功/)).toBeInTheDocument()
  })

  /**
   * 高风险保存如果被错误密码拒绝，页面应该提示密码错误而不是 HTTP 状态码。
   */
  it('shows a friendly password error when settings save is rejected', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
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
      if (url.includes('/api/config/bili-config') && init?.method === 'POST') {
        return {ok: false, status: 401, json: async () => ({message: 'bad password'})}
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
      recentPushRecords: [{timestampEpochMillis: 1_700_000_000_000, type: 'LIVE', typeLabel: '直播', success: true, statusLabel: '推送成功', summary: '测试UP主', target: '群 100'}],
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
    expect(screen.getByText('直播')).toBeInTheDocument()
    expect(screen.getByText('推送成功')).toBeInTheDocument()
    expect(screen.getByText('测试UP主')).toBeInTheDocument()
    expect(screen.getByText(/2023/)).toBeInTheDocument()
    expect(screen.getByText('120G/256G')).toBeInTheDocument()
    expect(screen.queryByText('50%')).not.toBeInTheDocument()
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
    expect(screen.getByText('本页面所有配置项都需重启程序生效')).toBeInTheDocument()
    expect(screen.getByText('本页面所有配置项都需重启程序生效')).toHaveClass('text-rose-600')
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
   * 群普通管理员使用群聊和个人 QQ 双栏输入，并在下方展示已有映射。
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
    expect(screen.getByText('群聊：124515 管理员：1245512')).toBeInTheDocument()
  })

  /**
   * 群普通管理员卡片先暂存到页面态，只有右上角保存才会触发后端写入。
   */
  it('stages group admin pairs from the card save button before the page save writes them', async () => {
    const postRequests: Array<{url: string, body: string}> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        postRequests.push({url, body: String(init.body || '')})
        return {ok: true, status: 200, json: async () => ({success: true, message: 'bot saved'})}
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
    const emptyState = screen.getByText('暂无群普通管理员')
    const stageButton = screen.getByRole('button', {name: '暂存'})
    expect(emptyState.compareDocumentPosition(stageButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await user.click(stageButton)
    expect(screen.getByText('群聊：123456 管理员：654321')).toBeInTheDocument()
    expect(screen.getByText('群聊：111111 管理员：222222')).toBeInTheDocument()
    expect(postRequests).toHaveLength(0)

    await user.click(screen.getByRole('button', {name: '保存'}))
    await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    await waitFor(() => expect(postRequests.length).toBeGreaterThan(0))
    if (!postRequests.some((request) => request.url.includes('/api/config/bot'))) {
      await user.type(await screen.findByLabelText('确认密码'), 'settings-password')
      await user.click(screen.getByRole('button', {name: '确认'}))
    }

    await waitFor(() => expect(postRequests.some((request) => request.url.includes('/api/config/bot'))).toBe(true))
    const botPost = postRequests.find((request) => request.url.includes('/api/config/bot'))
    expect(botPost?.body).toContain('123456')
    expect(botPost?.body).toContain('654321')
    expect(botPost?.body).toContain('111111')
    expect(botPost?.body).toContain('222222')
  })

  /**
   * 管理员页连续保存时必须沿用后端返回的新快照令牌，避免第二次保存被并发保护误判为旧快照。
   */
  it('uses the latest bot snapshot token when saving group admins repeatedly', async () => {
    const botPostBodies: Array<Record<string, unknown>> = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config')) {
        return {ok: true, status: 200, json: async () => ({sourceFile: 'BiliConfig.yml', snapshotToken: '', fields: []})}
      }
      if (url.includes('/api/config/bot') && init?.method === 'POST') {
        botPostBodies.push(JSON.parse(String(init.body || '{}')))
        return {
          ok: true,
          status: 200,
          json: async () => ({success: true, message: 'bot saved', snapshotToken: `bot-token-${botPostBodies.length + 1}`}),
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
    await waitFor(() => expect(botPostBodies).toHaveLength(1))

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
   * 只修改群普通管理员时不应写入 BiliConfig，避免无关旧配置校验失败挡住 bot.yml 保存。
   */
  it('does not save BiliConfig when only group admins changed', async () => {
    const postUrls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        postUrls.push(url)
      }
      if (url.includes('/api/config/bili-config') && init?.method === 'POST') {
        return {ok: false, status: 400, json: async () => ({success: false, message: 'lowSpeedRange is invalid'})}
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
      if (url.includes('/api/config/bot') && init?.method === 'POST') {
        return {ok: true, status: 200, json: async () => ({success: true, message: 'bot saved', snapshotToken: 'bot-token-2'})}
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

    await waitFor(() => expect(postUrls.some((url) => url.includes('/api/config/bot'))).toBe(true))
    expect(postUrls.some((url) => url.includes('/api/config/bili-config'))).toBe(false)
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
    window.localStorage.removeItem('dynamic_bot_webui_theme')
    document.cookie = 'dynamic_bot_webui_theme=dark; path=/'
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
    document.cookie = 'dynamic_bot_webui_theme=; Max-Age=0; path=/'
    window.localStorage.removeItem('dynamic_bot_webui_theme')
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
    expect(screen.getByRole('button', {name: '刷新'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '导出'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '清空'})).toBeInTheDocument()
    const logWindow = document.querySelector('pre')
    expect(logWindow).not.toBeNull()
    await waitFor(() => expect((logWindow as HTMLPreElement).scrollTop).toBe(640))
  })

  /**
   * 订阅页需要恢复旧 WebUI 的列表筛选、新增模式和嵌套编辑器入口。
   */
  it('renders subscription filters, create modes, and nested editor controls', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
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
      if (url.endsWith('/api/subscriptions/sub-1/filters')) {
        return {ok: true, status: 200, json: async () => ({filters: [{key: 'filter-1', kind: 'regex', mode: 'black', content: '广告', label: '正则', prefix: '黑名单'}]})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/templates')) {
        return {ok: true, status: 200, json: async () => ({templates: [{key: 'template-1', type: 'dynamic', typeLabel: '动态', name: '默认模板', content: '{{title}}'}], randomEnabled: true})}
      }
      if (url.endsWith('/api/subscriptions/sub-1/atall')) {
        return {ok: true, status: 200, json: async () => ({items: [{key: '全部动态', type: '全部动态', summary: '全部动态：10001', groups: ['10001']}]})}
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
    const editorOverlay = document.querySelector('[data-subscription-editor-overlay]')
    const editorPanel = document.querySelector('[data-subscription-editor-panel]')
    expect(editorOverlay).not.toBeNull()
    expect(editorOverlay).toHaveClass('inset-0')
    expect(editorOverlay).not.toHaveClass('lg:left-60')
    expect(editorPanel).not.toBeNull()
    expect(editorPanel).toHaveClass('lg:row-start-2')
    expect(editorPanel).toHaveClass('lg:max-w-md')
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
    await user.type(screen.getByLabelText('规则内容'), '广告')
    await user.click(screen.getByRole('button', {name: '保存过滤器'}))
    await user.type(await screen.findByLabelText('确认密码'), 'filter-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    expect(await screen.findByText('过滤器已保存')).toBeInTheDocument()

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
    await user.type(screen.getByLabelText('模板名称'), '默认模板')
    await user.type(screen.getByLabelText('模板内容'), '{{title}}')
    await user.click(screen.getByRole('button', {name: '保存模板'}))
    await user.type(await screen.findByLabelText('确认密码'), 'template-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    expect(await screen.findByText('模板已保存')).toBeInTheDocument()

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
    const editorDialog = screen.getByRole('dialog', {name: '编辑订阅配置'})
    expect(await screen.findByLabelText('主题颜色')).toHaveValue('#33aaff')
    await user.clear(screen.getByLabelText('主题颜色'))
    await user.type(screen.getByLabelText('主题颜色'), 'not-a-color')
    await user.click(screen.getByRole('button', {name: '保存主题色'}))
    expect(within(editorDialog).getByRole('alert')).toHaveTextContent('主题颜色必须是 HEX 颜色')
    await user.clear(screen.getByLabelText('主题颜色'))
    await user.type(screen.getByLabelText('主题颜色'), '#33aaff')
    await user.click(screen.getByRole('button', {name: '保存主题色'}))
    await user.type(await screen.findByLabelText('确认密码'), 'theme-password')
    await user.click(screen.getByRole('button', {name: '确认'}))
    expect(await screen.findByText('主题色已保存')).toBeInTheDocument()
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
    expect(document.querySelector('aside > div.fixed.bottom-0.left-0')).not.toBeNull()
    expect(within(aside as HTMLElement).getByLabelText('主题模式')).toBeInTheDocument()
    expect(within(aside as HTMLElement).getByRole('button', {name: '管理员', expanded: false})).toBeInTheDocument()
    expect(within(aside as HTMLElement).getByLabelText('主题模式').parentElement).toHaveClass('space-y-1')
    expect(within(aside as HTMLElement).getByRole('button', {name: '管理员', expanded: false})).toHaveClass('w-full')
    expect(within(aside as HTMLElement).getByRole('button', {name: '管理员', expanded: false})).toHaveClass('justify-start')
    expect(document.querySelector('header')).toBeNull()

    // 侧边栏底部控件需要脱离主内容流，始终固定在视口左下角。
    expect(readFileSync('src/components/Shell.tsx', 'utf8')).toContain('fixed bottom-0 left-0')

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
