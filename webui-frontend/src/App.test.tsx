import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'

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

describe('webui shell routing', () => {
  it('renders the dashboard shell with the core navigation pages', () => {
    renderAtPath('/')

    expect(screen.getByRole('heading', {name: '动态机器人 WebUI'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '首页'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '系统配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '订阅管理'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '日志'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '管理员菜单'})).toBeInTheDocument()
  })

  it('switches pages when the shell navigation is used', () => {
    renderAtPath('/')

    fireEvent.click(screen.getByRole('button', {name: '系统配置'}))

    expect(screen.getByText('写入设置')).toBeInTheDocument()
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
    expect(screen.getByRole('button', {name: '管理员'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '翻译配置'})).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: 'B站配置'}))
    expect(await screen.findByLabelText('Cookie')).toHaveValue('')
    expect(screen.getByLabelText('代理地址')).toHaveValue('')
    expect(screen.queryByDisplayValue('SECRET_COOKIE')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('http://secret-proxy')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: '对接配置'}))
    expect(await screen.findByLabelText('OneBot11 Token')).toHaveValue('')
    expect(screen.queryByDisplayValue('SECRET_TOKEN')).not.toBeInTheDocument()
  })

  it('keeps a dense React layout for dashboard cards and account modal', () => {
    renderAtPath('/')

    expect(screen.getByText('运行概览')).toBeInTheDocument()
    expect(screen.getByText('配置入口')).toBeInTheDocument()
    expect(screen.getByText('日志窗口')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: '管理员菜单'}))
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
      webSocket: {connected: true, reconnectAttempts: 0, activeSessionCount: 2, transports: ['onebot11'], note: 'ready'},
      todayPushStats: {date: '2026-05-23', total: 7, dynamic: 5, live: 1, liveClose: 1, failed: 0, lastSuccessAtEpochMillis: 1_700_000_000_000},
      recentPushRecords: [{timestampEpochMillis: 1_700_000_000_000, type: 'dynamic', typeLabel: '动态', success: true, statusLabel: '成功', summary: '动态更新', target: '群 100'}],
      host: {
        startedAtEpochMillis: 1_700_000_000_000,
        systemTimeEpochMillis: 1_700_007_200_000,
        systemLoadAverage: 0.5,
        cpuUsagePercent: 12,
        memory: {usedBytes: 1024, totalBytes: 2048, usagePercent: 50},
        storage: {usedBytes: 2048, totalBytes: 4096, usagePercent: 50},
        docker: {detected: true, evidence: 'container'},
      },
    })

    renderAtPath('/')

    await waitFor(() => expect(screen.getByText('2.0.0')).toBeInTheDocument())
    expect(screen.getByText('UID 12345')).toBeInTheDocument()
    expect(screen.getByText('已连接')).toBeInTheDocument()
    expect(screen.getByText('7 条')).toBeInTheDocument()
    expect(screen.getByText('动态更新')).toBeInTheDocument()
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
    expect(screen.getByText('Docker')).toBeInTheDocument()
    expect(screen.getByText('今日推送')).toBeInTheDocument()
    expect(screen.getByText('最近推送')).toBeInTheDocument()
  })

  it('renders the login screen for the login path', () => {
    renderAtPath('/login')

    expect(screen.getByRole('heading', {name: '登录'})).toBeInTheDocument()
    expect(screen.getByLabelText('WebUI 密码')).toBeInTheDocument()
    expect(screen.queryByRole('button', {name: '管理员菜单'})).not.toBeInTheDocument()
  })
})
