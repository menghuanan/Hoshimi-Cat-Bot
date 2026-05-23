import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ConfirmationContext, type ConfirmationContextValue } from './confirmationContextValue'

type ConfirmationRequest = {
  message: string
  title: string
}

/**
 * 单个 provider 托管所有高风险确认弹窗，避免每个页面各自挂一套 modal state。
 */
export function ConfirmationProvider({children}: {children: ReactNode}) {
  const resolverRef = useRef<((value: string) => void) | null>(null)
  const [request, setRequest] = useState<ConfirmationRequest | null>(null)
  const [password, setPassword] = useState('')

  const closeRequest = useCallback((value: string) => {
    const resolver = resolverRef.current
    resolverRef.current = null
    setRequest(null)
    setPassword('')
    resolver?.(value)
  }, [])

  const openRequest = useCallback((nextRequest: ConfirmationRequest) => {
    if (resolverRef.current) {
      closeRequest('')
    }
    return new Promise<string>((resolve) => {
      resolverRef.current = resolve
      setRequest(nextRequest)
    })
  }, [closeRequest])

  const value = useMemo<ConfirmationContextValue>(() => ({
    requestHighRiskConfirmation: (message: string) => openRequest({
      message,
      title: '密码确认',
    }),
  }), [openRequest])

  useEffect(() => {
    if (!request) {
      return undefined
    }
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        closeRequest('')
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [closeRequest, request])

  return (
    <ConfirmationContext.Provider value={value}>
      {children}
      {request ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-950/50 px-4 py-6"
          role="presentation"
          onMouseDown={() => closeRequest('')}
        >
          <section
            aria-modal="true"
            role="dialog"
            aria-labelledby="confirmation-title"
            className="w-full max-w-md rounded-xl border border-zinc-200 bg-white p-6 shadow-2xl"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <h2 id="confirmation-title" className="text-lg font-semibold text-zinc-950">
              {request.title}
            </h2>
            <p className="mt-3 text-sm leading-6 text-zinc-600">{request.message}</p>
            <label className="mt-5 block">
              <span className="mb-2 block text-sm font-medium text-zinc-700">确认密码</span>
              <input
                autoFocus
                aria-label="确认密码"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                className="w-full rounded-lg border border-zinc-300 px-3 py-2 text-sm"
              />
            </label>
            <div className="mt-6 flex justify-end gap-3">
              <button
                type="button"
                className="rounded-lg border border-zinc-300 px-4 py-2 text-sm font-medium text-zinc-700"
                onClick={() => closeRequest('')}
              >
                取消
              </button>
              <button
                type="button"
                className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white"
                onClick={() => closeRequest(password.trim())}
              >
                确认
              </button>
            </div>
          </section>
        </div>
      ) : null}
    </ConfirmationContext.Provider>
  )
}
