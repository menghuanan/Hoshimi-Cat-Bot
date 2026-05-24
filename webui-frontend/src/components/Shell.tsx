import { useEffect, useRef, useState, type ReactNode } from 'react'
import { changePassword, logout } from '../api/auth'
import { useThemePreference } from '../hooks/useThemePreference'
import type { WebUiPageName } from '../router/webuiRouter'
import { formatPasswordErrorMessage } from '../utils/errorMessages'

type ShellProps = {
  page: Exclude<WebUiPageName, 'login'>
  onNavigate: (page: Exclude<WebUiPageName, 'login'>) => void
  children: ReactNode
}

/**
 * 壳层负责导航、侧边栏系统入口和内容承载区，页面内容由子页面组件填充。
 */
export function Shell({page, onNavigate, children}: ShellProps) {
  const {preference, setPreference} = useThemePreference()
  const [adminMenuOpen, setAdminMenuOpen] = useState(false)
  const [passwordModalOpen, setPasswordModalOpen] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [accountMessage, setAccountMessage] = useState('')
  const [accountPending, setAccountPending] = useState(false)
  const adminMenuRef = useRef<HTMLDivElement | null>(null)

  /**
   * 改密弹窗支持 Escape 关闭，和全局确认弹窗保持一致的键盘行为。
   */
  useEffect(() => {
    if (!passwordModalOpen) {
      return undefined
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setPasswordModalOpen(false)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [passwordModalOpen])

  /**
   * 管理员下拉菜单跟随页面空白区域点击关闭，保持侧边栏弹层和旧版交互一致。
   */
  useEffect(() => {
    if (!adminMenuOpen) {
      return undefined
    }
    const handleMouseDown = (event: MouseEvent) => {
      const target = event.target
      if (!(target instanceof Node)) {
        return
      }
      if (adminMenuRef.current?.contains(target)) {
        return
      }
      setAdminMenuOpen(false)
    }
    document.addEventListener('mousedown', handleMouseDown)
    return () => {
      document.removeEventListener('mousedown', handleMouseDown)
    }
  }, [adminMenuOpen])

  /**
   * 改密弹窗复用认证 API，成功后直接回到登录页重新认证。
   */
  const submitPasswordChange = async () => {
    if (newPassword !== confirmPassword) {
      setAccountMessage('新密码和确认密码不一致')
      return
    }
    setAccountPending(true)
    setAccountMessage('')
    try {
      await changePassword(currentPassword, newPassword)
      window.location.assign('/login')
    } catch (error) {
      setAccountMessage(formatPasswordErrorMessage(error, '修改密码失败'))
    } finally {
      setAccountPending(false)
    }
  }

  /**
   * 登出先调用后端撤销会话，再直接回到登录页。
   */
  const submitLogout = async () => {
    setAccountPending(true)
    setAccountMessage('')
    try {
      await logout()
    } finally {
      window.location.assign('/login')
    }
  }

  return (
    <main className="min-h-screen overflow-x-hidden bg-slate-100 text-slate-950">
      <div className="grid min-h-screen w-full grid-cols-[18rem_minmax(0,1fr)] max-lg:grid-cols-1">
        {/* 桌面侧边栏整体固定在视口左侧，移动端折叠布局保持原样。 */}
        <aside className="fixed inset-y-0 left-0 z-30 flex h-full w-72 flex-col border-r border-slate-200 bg-white px-5 py-6 max-lg:static max-lg:w-full max-lg:border-b max-lg:border-r-0">
          <div className="mb-6">
            <p className="text-sm font-semibold uppercase tracking-wide text-slate-500">dynamic-bot</p>
            <h1 className="mt-2 text-xl font-semibold text-slate-950">动态机器人 WebUI</h1>
          </div>
          <nav className="grid gap-2.5 max-lg:grid-cols-4 max-sm:grid-cols-2">
            {[
              ['home', '首页'],
              ['settings', '系统配置'],
              ['subscriptions', '订阅管理'],
              ['logs', '日志'],
            ].map(([target, label]) => (
              <button
                key={target}
                type="button"
                data-nav-target={target}
                aria-pressed={page === target}
                className={`flex w-full items-center rounded-lg px-4 py-2.5 text-left text-base font-medium transition ${
                  page === target ? 'bg-slate-950 text-white shadow-sm' : 'text-slate-700 hover:bg-slate-100'
                }`}
                onClick={() => onNavigate(target as Exclude<WebUiPageName, 'login'>)}
              >
                {label}
              </button>
            ))}
          </nav>
          {/* 底部控制区保留完整卡片感，但通过左侧负外边距和右侧额外 1px 贴齐侧栏外沿，抵消侧栏自身边框宽度。 */}
          <div className="mt-auto border-t border-r border-slate-200 bg-white/95 px-4 pb-4 pt-3 shadow-[0_-8px_24px_rgba(15,23,42,0.06)] backdrop-blur lg:-ml-5 lg:mr-[-21px] max-lg:mt-6 max-lg:mx-0 max-lg:w-full max-lg:border-t-0 max-lg:border-r-0 max-lg:bg-transparent max-lg:px-0 max-lg:pb-0 max-lg:pt-4 max-lg:shadow-none">
            <div className="space-y-3">
              <label className="block space-y-1 text-sm font-medium text-slate-700">
                <span className="block pl-1 text-xs font-semibold uppercase tracking-wide text-slate-500">主题模式</span>
                <select
                  value={preference}
                  onChange={(event) => setPreference(event.target.value as 'system' | 'light' | 'dark')}
                  className="w-full rounded-xl border border-slate-300 bg-white px-4 py-3 text-sm text-slate-800 shadow-sm transition hover:bg-slate-50"
                >
                  <option value="light">亮色</option>
                  <option value="dark">暗色</option>
                  <option value="system">跟随系统</option>
                </select>
              </label>
              <div ref={adminMenuRef} className="relative">
                <button
                  type="button"
                  className="flex w-full items-center justify-start rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 text-left text-sm font-semibold text-slate-800 shadow-sm transition hover:bg-slate-100"
                  aria-haspopup="menu"
                  aria-expanded={adminMenuOpen}
                  onClick={() => setAdminMenuOpen((current) => !current)}
                >
                  管理员
                </button>
                {adminMenuOpen ? (
                  <div
                    role="menu"
                    className="absolute bottom-full left-0 z-30 mb-2 w-full rounded-xl border border-slate-200 bg-white p-2 shadow-lg"
                  >
                    <button
                      type="button"
                      className="block w-full rounded-lg px-3 py-2 text-left text-sm hover:bg-slate-100"
                      onClick={() => {
                        setPasswordModalOpen(true)
                        setAdminMenuOpen(false)
                      }}
                    >
                      修改密码
                    </button>
                    <button type="button" disabled={accountPending} className="block w-full rounded-lg px-3 py-2 text-left text-sm text-rose-700 hover:bg-rose-50 disabled:opacity-50" onClick={() => void submitLogout()}>
                      退出登录
                    </button>
                  </div>
                ) : null}
              </div>
            </div>
          </div>
        </aside>
        {/* 主内容区在桌面端占据第二列，给固定侧栏留出稳定的视觉空间。 */}
        <div className="flex min-w-0 flex-col max-lg:col-start-auto lg:col-start-2">
          {/* 主内容区直接贴靠壳层顶部，页面会在删除标题后自然上移。 */}
          <section className="min-w-0 flex-1 px-6 py-6 max-sm:px-4">
            <div className="mx-auto w-full max-w-7xl">
              {children}
            </div>
          </section>
        </div>
      </div>
      {passwordModalOpen ? (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/50 px-4 py-6" role="presentation" onMouseDown={() => setPasswordModalOpen(false)}>
          <section className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-6 shadow-2xl" role="dialog" aria-modal="true" aria-labelledby="account-password-title" onMouseDown={(event) => event.stopPropagation()}>
            <h3 id="account-password-title" className="text-base font-semibold text-slate-950">修改密码</h3>
            <div className="mt-5 space-y-4">
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700">当前密码</span>
                <input value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} type="password" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700">新密码</span>
                <input value={newPassword} onChange={(event) => setNewPassword(event.target.value)} type="password" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700">确认新密码</span>
                <input value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} type="password" className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
            </div>
            {accountMessage ? <p className="mt-4 text-sm font-medium text-rose-700">{accountMessage}</p> : null}
            <div className="mt-6 flex justify-end gap-3">
              <button type="button" className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700" onClick={() => setPasswordModalOpen(false)}>取消</button>
              <button type="button" disabled={accountPending} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-400" onClick={() => void submitPasswordChange()}>
                {accountPending ? '提交中' : '确认'}
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </main>
  )
}
