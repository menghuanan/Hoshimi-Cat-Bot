import { useState, type ReactNode } from 'react'
import { changePassword, logout } from '../api/auth'
import type { WebUiPageName } from '../router/webuiRouter'
import { clearWebUiToken } from '../utils/storage'

type ShellProps = {
  page: Exclude<WebUiPageName, 'login'>
  onNavigate: (page: Exclude<WebUiPageName, 'login'>) => void
  children: ReactNode
}

/**
 * 壳层负责导航、顶部管理员菜单和内容承载区，页面内容由子页面组件填充。
 */
export function Shell({page, onNavigate, children}: ShellProps) {
  const [adminMenuOpen, setAdminMenuOpen] = useState(false)
  const [passwordModalOpen, setPasswordModalOpen] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [accountMessage, setAccountMessage] = useState('')
  const [accountPending, setAccountPending] = useState(false)

  /**
   * 改密弹窗复用认证 API，成功后清理 token 并回到登录页重新认证。
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
      clearWebUiToken()
      window.location.assign('/login')
    } catch (error) {
      setAccountMessage(error instanceof Error ? error.message : '修改密码失败')
    } finally {
      setAccountPending(false)
    }
  }

  /**
   * 登出先调用后端撤销会话，再清理本地 Bearer token。
   */
  const submitLogout = async () => {
    setAccountPending(true)
    setAccountMessage('')
    try {
      await logout()
    } finally {
      clearWebUiToken()
      window.location.assign('/login')
    }
  }

  return (
    <main className="min-h-screen overflow-x-hidden bg-slate-100 text-slate-950">
      <div className="grid min-h-screen w-full grid-cols-[15rem_minmax(0,1fr)] max-lg:grid-cols-1">
        <aside className="border-r border-slate-200 bg-white px-4 py-5 max-lg:border-b max-lg:border-r-0">
          <div className="mb-6">
            <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">dynamic-bot</p>
            <h1 className="mt-2 text-lg font-semibold text-slate-950">动态机器人 WebUI</h1>
          </div>
          <nav className="grid gap-2 max-lg:grid-cols-4 max-sm:grid-cols-2">
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
                className={`flex w-full items-center rounded-lg px-3 py-2 text-left text-sm font-medium transition ${
                  page === target ? 'bg-slate-950 text-white shadow-sm' : 'text-slate-700 hover:bg-slate-100'
                }`}
                onClick={() => onNavigate(target as Exclude<WebUiPageName, 'login'>)}
              >
                {label}
              </button>
            ))}
          </nav>
        </aside>
        <div className="flex min-w-0 flex-col">
          <header className="sticky top-0 z-20 flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-white/95 px-6 py-4 backdrop-blur max-sm:flex-col max-sm:items-stretch max-sm:px-4">
            <div className="min-w-0">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">WebUI</p>
              <h2 className="text-lg font-semibold text-slate-950">{pageLabel(page)}</h2>
            </div>
            <div className="relative shrink-0 max-sm:w-full">
              <button
                type="button"
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-800 hover:bg-slate-50 max-sm:w-full"
                aria-haspopup="menu"
                aria-expanded={adminMenuOpen}
                onClick={() => setAdminMenuOpen((current) => !current)}
              >
                管理员菜单
              </button>
              {adminMenuOpen ? (
                <div
                  role="menu"
                  className="absolute right-0 z-30 mt-2 w-44 rounded-lg border border-slate-200 bg-white p-2 shadow-lg"
                >
                  <button
                    type="button"
                    className="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-slate-100"
                    onClick={() => {
                      setPasswordModalOpen(true)
                      setAdminMenuOpen(false)
                    }}
                  >
                    修改密码
                  </button>
                  <button type="button" disabled={accountPending} className="block w-full rounded-md px-3 py-2 text-left text-sm text-rose-700 hover:bg-rose-50 disabled:opacity-50" onClick={() => void submitLogout()}>
                    退出登录
                  </button>
                </div>
              ) : null}
            </div>
          </header>
          <section className="min-w-0 flex-1 px-6 py-6 max-sm:px-4">
            {children}
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

/**
 * 页面标题和导航标签共用同一份枚举映射，避免中文文案在各处重复。
 */
function pageLabel(page: Exclude<WebUiPageName, 'login'>): string {
  const labels = {
    home: '首页',
    settings: '系统配置',
    subscriptions: '订阅管理',
    logs: '日志',
  }
  return labels[page]
}
