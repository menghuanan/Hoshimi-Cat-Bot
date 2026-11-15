import { useEffect, useRef, useState } from 'react'
import { loadQQAdminBinding, startQQAdminBinding, type QQAdminBindingSession } from '../api/qqAdminBinding'
import { formatPasswordErrorMessage } from '../utils/errorMessages'
import { ModalPortal } from './ModalPortal'

/** 绑定弹窗沿用现有 Portal、边框和配色；卸载或重试时隔离迟到响应并回收轮询。 */
export function QQAdminBindingModal({onClose, onSucceeded}: {
  onClose: () => void
  onSucceeded: () => void | Promise<void>
}) {
  const [session, setSession] = useState<QQAdminBindingSession | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const dialogRef = useRef<HTMLElement>(null)

  useEffect(() => {
    let active = true
    let timer: number | undefined
    let current: QQAdminBindingSession | null = null

    /** 下一轮只在本轮请求完成后调度，暂时查询失败保留同一个会话重试。 */
    const poll = async () => {
      try {
        const next = current ? await loadQQAdminBinding(current.sessionId) : await startQQAdminBinding()
        if (!active) return
        current = next
        setSession(next)
        setError('')
        if (next.phase === 'SUCCEEDED') {
          // 成功只处理一次；页面刷新失败不得把已完成的绑定改写成失败或重发创建请求。
          void Promise.resolve(onSucceeded()).catch(() => {
            if (active) setError('绑定已完成，请刷新页面查看最新配置')
          })
        }
      } catch (cause) {
        if (!active) return
        setError(formatPasswordErrorMessage(cause, '获取绑定状态失败'))
      }
      if (active && current?.phase === 'WAITING') {
        // 网络持续不可用时也在本地到期，避免弹窗无限轮询。
        if (Date.now() >= current.expiresAtEpochMillis) {
          setSession({...current, phase: 'EXPIRED', code: null})
        } else {
          timer = window.setTimeout(() => void poll(), 2_000)
        }
      }
    }
    timer = window.setTimeout(() => void poll(), 0)
    return () => {
      active = false
      window.clearTimeout(timer)
    }
  }, [attempt, onSucceeded])

  useEffect(() => {
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = dialogRef.current
    dialog?.focus()
    /** Escape 关闭视图，Tab 焦点限制在弹窗内，防止键盘操作落到遮罩下的配置表单。 */
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
      if (event.key !== 'Tab') return
      const controls = Array.from(dialog?.querySelectorAll<HTMLElement>('button, [tabindex="0"]') || [])
      const first = controls[0]
      const last = controls[controls.length - 1]
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) {
        event.preventDefault()
        last?.focus()
      } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog)) {
        event.preventDefault()
        first?.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      previousFocus?.focus()
    }
  }, [onClose])

  /** 仅显式重试创建新轮次；清理旧界面后由 effect 处理请求和代际隔离。 */
  const retry = () => {
    setSession(null)
    setError('')
    setAttempt((value) => value + 1)
  }

  return (
    <ModalPortal>
      <div className="modal-overlay fixed inset-0 z-50 flex items-center justify-center px-4 py-6" role="presentation"
        onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
        <section ref={dialogRef} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="qq-admin-binding-title"
          className="w-full max-w-md overflow-hidden rounded-lg border border-slate-200 bg-white shadow-2xl">
          <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
            <h2 id="qq-admin-binding-title" className="text-base font-semibold text-slate-950">快速绑定</h2>
            <button type="button" aria-label="关闭快速绑定" className="text-xl text-slate-500 hover:text-slate-900" onClick={onClose}>×</button>
          </div>
          <div className="space-y-4 px-5 py-6 text-sm text-slate-700" aria-live="polite">
            {!session && !error ? <p>正在生成绑定代码…</p> : null}
            {session?.phase === 'WAITING' ? <>
              <p>请在QQ内添加bot好友后发送以下代码：</p>
              <code tabIndex={0} className="block select-all break-all rounded-lg border border-slate-200 bg-slate-50 p-4 text-center font-mono text-base font-semibold text-slate-950">{session.code}</code>
              <p>后等待绑定完成。</p>
              <p className="text-xs text-slate-500">请在 10 分钟内私聊发送，勿转发给他人。关闭弹窗后代码仍在有效期内可用。</p>
            </> : null}
            {session?.phase === 'SUCCEEDED' ? <p className="font-semibold text-emerald-700">绑定完成，管理员已保存。</p> : null}
            {session?.phase === 'EXPIRED' ? <p>绑定代码已过期或配置已变更，请重新获取。</p> : null}
            {session?.phase === 'FAILED' ? <p>管理员保存失败，请检查配置文件写入权限后重试。</p> : null}
            {error ? <p role="alert" className="text-rose-700">{error}</p> : null}
          </div>
          <div className="flex justify-end gap-3 border-t border-slate-200 bg-slate-50 px-5 py-4">
            {(!session && error) || session?.phase === 'EXPIRED' || session?.phase === 'FAILED'
              ? <button type="button" className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white" onClick={retry}>重新获取</button> : null}
            <button type="button" className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700" onClick={onClose}>关闭</button>
          </div>
        </section>
      </div>
    </ModalPortal>
  )
}
