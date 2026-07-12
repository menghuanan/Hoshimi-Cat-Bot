import { useEffect, useState } from 'react'
import type { WebUiBiliLoginSession } from '../types/biliLogin'
import { ModalPortal } from './ModalPortal'

type BiliQrLoginModalProps = {
  open: boolean
  loading: boolean
  session: WebUiBiliLoginSession | null
  error: string
  onClose: () => void | Promise<void>
  onRetry: () => void | Promise<void>
}

/** 终态失败允许原位重试，成功态由 hook 延迟自动关闭。 */
function canRetry(session: WebUiBiliLoginSession | null, error: string): boolean {
  return Boolean(error) || Boolean(session && ['EXPIRED', 'FAILED', 'CANCELLED'].includes(session.phase))
}

/** 二维码登录弹窗只展示脱敏状态和 PNG，不接触底层 URL、key 或 Cookie。 */
export function BiliQrLoginModal({open, loading, session, error, onClose, onRetry}: BiliQrLoginModalProps) {
  const [now, setNow] = useState(() => Date.now())
  const committing = session?.phase === 'COMMITTING'

  useEffect(() => {
    if (!open) return undefined
    // 有效期每秒刷新一次，仅影响倒计时文本，不驱动登录轮询。
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [open])

  useEffect(() => {
    if (!open || committing) return undefined
    // Escape 与遮罩共用取消语义，提交阶段不注册关闭快捷键。
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') void onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [committing, onClose, open])

  if (!open) return null
  const remainingSeconds = session
    ? Math.max(0, Math.ceil((session.expiresAtEpochMillis - now) / 1_000))
    : null
  // 活动会话错误只重试当前状态查询，终态错误才需要获取新二维码。
  const retryLabel = error && session && !['EXPIRED', 'FAILED', 'CANCELLED', 'SUCCEEDED'].includes(session.phase)
    ? '重试查询'
    : '重新获取二维码'

  return (
    <ModalPortal>
      <div
        className="modal-overlay fixed inset-0 z-50 flex items-center justify-center px-4 py-6"
        role="presentation"
        onMouseDown={(event) => {
          if (!committing && event.target === event.currentTarget) void onClose()
        }}
      >
        <section
          role="dialog"
          aria-modal="true"
          aria-labelledby="bili-qr-login-title"
          className="w-full max-w-md overflow-hidden rounded-lg border border-slate-200 bg-white shadow-2xl"
          onMouseDown={(event) => event.stopPropagation()}
        >
          <div className="flex items-start justify-between gap-4 border-b border-slate-200 px-5 py-4">
            <div className="min-w-0">
              <h2 id="bili-qr-login-title" className="text-base font-semibold text-slate-950">B站扫码登录</h2>
              <p className="mt-1 text-sm text-slate-500">{session?.message || (loading ? '正在获取二维码' : '准备登录')}</p>
            </div>
            {!committing ? (
              <button type="button" aria-label="关闭扫码登录" className="text-xl leading-none text-slate-500 hover:text-slate-900" onClick={() => void onClose()}>×</button>
            ) : null}
          </div>

          <div className="grid min-h-[22rem] place-items-center gap-4 px-5 py-6 text-center">
            {loading ? (
              <div className="grid place-items-center gap-3 text-sm text-slate-600">
                <span className="button-spinner" aria-hidden="true" />
                <span>正在获取二维码</span>
              </div>
            ) : null}
            {!loading && session?.qrImageBase64 ? (
              <img
                src={`data:image/png;base64,${session.qrImageBase64}`}
                alt="B站登录二维码"
                width={250}
                height={250}
                className="h-[250px] w-[250px] border border-slate-200 bg-white p-2"
              />
            ) : null}
            {!loading && error ? <p role="alert" className="max-w-sm text-sm text-rose-700">{error}</p> : null}
            {!loading && !error && session ? (
              <div className="space-y-1 text-sm">
                <p className={session.phase === 'SUCCEEDED' ? 'font-semibold text-emerald-700' : 'font-medium text-slate-800'}>{session.message}</p>
                {remainingSeconds !== null && !['COMMITTING', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(session.phase) ? (
                  <p className="text-slate-500">剩余 {remainingSeconds} 秒</p>
                ) : null}
              </div>
            ) : null}
          </div>

          <div className="flex justify-end gap-3 border-t border-slate-200 bg-slate-50 px-5 py-4">
            {canRetry(session, error) ? (
              <button type="button" className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white" onClick={() => void onRetry()}>{retryLabel}</button>
            ) : null}
            {!committing ? (
              <button type="button" className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700" onClick={() => void onClose()}>
                {session && ['EXPIRED', 'FAILED', 'CANCELLED'].includes(session.phase) ? '关闭' : '取消登录'}
              </button>
            ) : (
              <span className="px-1 py-2 text-sm font-medium text-slate-500">正在提交，请稍候</span>
            )}
          </div>
        </section>
      </div>
    </ModalPortal>
  )
}
