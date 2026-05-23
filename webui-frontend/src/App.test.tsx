import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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

describe('webui shell routing', () => {
  it('renders the dashboard shell with the core navigation pages', () => {
    renderAtPath('/')

    expect(screen.getByRole('heading', {name: '动态机器人 WebUI'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '首页'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '系统配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '订阅管理'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '日志'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Admin'})).toBeInTheDocument()
  })

  it('switches pages when the shell navigation is used', () => {
    renderAtPath('/')

    fireEvent.click(screen.getByRole('button', {name: '系统配置'}))

    expect(screen.getByText('写入设置')).toBeInTheDocument()
  })

  /**
   * Ktor 直接服务 /settings、/subscriptions 和 /logs，React 路由也必须识别这些刷新入口。
   */
  it('renders protected direct path routes without requiring hash navigation', () => {
    const settingsRender = renderAtPath('/settings')
    expect(screen.getByText('写入设置')).toBeInTheDocument()
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
    expect(screen.getByRole('button', {name: '管理员'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '翻译配置'})).toBeInTheDocument()

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
    }])).toBe('保存失败：oneBot11Port is invalid')

    expect(formatSaveResultMessage([{
      success: true,
      message: 'bot.yml saved',
    }])).toBe('保存成功：bot.yml saved')
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

    fireEvent.click(screen.getByRole('button', {name: 'Admin'}))
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

    await user.selectOptions(screen.getByLabelText('平台类型'), 'qq_official')
    expect(screen.getByLabelText('QQ App ID')).toBeInTheDocument()
    expect(screen.queryByLabelText('OneBot11 主机')).not.toBeInTheDocument()

    await user.click(screen.getByLabelText('启用 WebUI'))
    expect(screen.getByLabelText('WebUI 主机')).toBeInTheDocument()
    expect(screen.getByLabelText('WebUI 端口')).toBeInTheDocument()
    expect(screen.getByLabelText('会话有效秒数')).toBeInTheDocument()
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
    fireEvent.click(await screen.findByRole('button', {name: '管理员'}))

    expect(await screen.findByLabelText('群聊')).toHaveValue('124515')
    expect(screen.getByLabelText('个人QQ号')).toHaveValue('1245512')
    expect(screen.getByText('群聊：124515 管理员：1245512')).toBeInTheDocument()
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
    expect(screen.queryByRole('button', {name: 'Admin'})).not.toBeInTheDocument()
  })

  /**
   * 日志页需要提供旧 WebUI 等价的来源、过滤、搜索、自动刷新、导出和清空控件。
   */
  it('renders log filtering, auto-refresh, export, and clear controls', async () => {
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
              targets: ['10001', '10002'],
              filterInfo: '1 条过滤器',
              filterCount: 1,
              templateNames: ['默认模板'],
              templateCount: 1,
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

    await user.click(screen.getByRole('button', {name: '新增订阅'}))
    expect(screen.getByRole('dialog', {name: '新增订阅'})).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('订阅类型'), 'group')
    expect(screen.getByLabelText('分组名称')).toBeInTheDocument()
    expect(screen.getByLabelText('分组 UID')).toBeInTheDocument()
    expect(screen.getByLabelText('分组目标群')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '取消'}))

    await user.click(screen.getByRole('button', {name: '编辑'}))
    expect(screen.getByRole('dialog', {name: '编辑订阅配置'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑过滤器'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑模板'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑at全体'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '编辑主题色'})).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '编辑模板'}))
    expect(await screen.findByLabelText('随机模板')).toBeChecked()
    expect(screen.getByRole('button', {name: '添加模板'})).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '编辑at全体'}))
    expect(await screen.findByRole('button', {name: '添加at全体'})).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '添加at全体'}))
    expect(screen.getByLabelText('10001')).toBeInTheDocument()
    expect(screen.getByLabelText('10002')).toBeInTheDocument()

    await user.click(screen.getByRole('button', {name: '编辑主题色'}))
    expect(await screen.findByLabelText('主题颜色')).toHaveValue('#33aaff')
  })

  /**
   * Shell 顶部需要保留管理员操作、主题偏好和可键盘关闭的账号弹窗。
   */
  it('supports admin menu actions, theme preference, and Escape modal close', async () => {
    const user = userEvent.setup()

    renderAtPath('/')

    await user.selectOptions(screen.getByLabelText('主题模式'), 'dark')
    expect(document.documentElement.classList.contains('theme-dark')).toBe(true)
    expect(screen.getByRole('option', {name: '亮色'})).toBeInTheDocument()
    expect(screen.getByRole('option', {name: '暗色'})).toBeInTheDocument()
    expect(readFileSync('src/styles.css', 'utf8')).toContain('.theme-dark')
    expect(readFileSync('src/styles.css', 'utf8')).toContain('.theme-dark .border-l-emerald-500')
    expect(readFileSync('src/styles.css', 'utf8')).toContain('.subscription-info-block')

    await user.click(screen.getByRole('button', {name: 'Admin'}))
    expect(screen.getByRole('menu')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {name: '修改密码'}))
    expect(screen.getByRole('dialog', {name: '修改密码'})).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', {name: '修改密码'})).not.toBeInTheDocument()
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
