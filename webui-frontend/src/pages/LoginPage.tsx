import { useEffect, useState, type FormEvent } from 'react'
import { changePassword, loginWithPassword, restoreSession } from '../api/auth'
import { useThemePreference } from '../hooks/useThemePreference'
import { formatLoginErrorMessage } from '../utils/errorMessages'
import { useWebUiNavigation } from '../hooks/useWebUiNavigation'

/**
 * 登录页直接调用认证 API，首次登录需要改密时留在同一 React 路由内完成流程。
 */
export function LoginPage() {
  useThemePreference()
  const {navigate} = useWebUiNavigation()
  const [password, setPassword] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [mustChangePassword, setMustChangePassword] = useState(false)
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState(false)

  /**
   * 登录页先探测同源 cookie 会话，已经登录的用户不需要再手动输入密码。
   */
  useEffect(() => {
    let active = true
    void (async () => {
      try {
        const session = await restoreSession()
        if (!active) {
          return
        }
        if (session.authenticated && !session.mustChangePassword) {
          window.location.assign('/')
        }
      } catch {
        // 会话不存在时保持登录表单可用，不打断正常输入流程。
      }
    })()
    return () => {
      active = false
    }
  }, [])

  /**
   * 登录成功后由后端同源 cookie 维持会话，再跳回根页面。
   */
  const submitLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setPending(true)
    setMessage('')
    try {
      const response = await loginWithPassword(password)
      if (response.mustChangePassword) {
        setCurrentPassword(password)
        setMustChangePassword(true)
        setMessage('请先修改初始密码')
      } else {
        navigate('home')
      }
    } catch (error) {
      setMessage(formatLoginErrorMessage(error))
    } finally {
      setPending(false)
    }
  }

  /**
   * 改密表单只在首次登录流展示，成功后强制回到登录输入新密码。
   */
  const submitPasswordChange = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (newPassword !== confirmPassword) {
      setMessage('新密码和确认密码不一致')
      return
    }
    setPending(true)
    setMessage('')
    try {
      await changePassword(currentPassword, newPassword)
      setMustChangePassword(false)
      setPassword('')
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      setMessage('密码已修改，请重新登录')
    } catch (error) {
      setMessage(formatLoginErrorMessage(error))
    } finally {
      setPending(false)
    }
  }

  return (
    <main className="min-h-screen bg-slate-100 text-slate-950">
      <section className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6 py-12">
        <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
          <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">Hoshimi-Cat-Bot</p>
          <h1 className="mt-2 text-2xl font-semibold text-slate-950">{mustChangePassword ? '修改初始密码' : '登录'}</h1>
          {mustChangePassword ? (
            <form className="mt-6 space-y-4" onSubmit={submitPasswordChange}>
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700">新密码</span>
                <input aria-label="新密码" type="password" value={newPassword} onChange={(event) => setNewPassword(event.target.value)} className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700">确认新密码</span>
                <input aria-label="确认新密码" type="password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <button type="submit" disabled={pending} className="w-full rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-400">
                {pending ? '提交中' : '修改密码'}
              </button>
            </form>
          ) : (
          <form className="mt-6 space-y-4" onSubmit={submitLogin}>
            <label className="block">
              <span className="mb-2 block text-sm font-medium text-slate-700">WebUI 密码</span>
              <input
                aria-label="WebUI 密码"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              />
            </label>
            <button
              type="submit"
              disabled={pending}
              className="w-full rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-400"
            >
              {pending ? '登录中' : '登录'}
            </button>
          </form>
          )}
          {message ? <p className="mt-4 text-sm font-medium text-slate-700">{message}</p> : null}
        </div>
      </section>
    </main>
  )
}
