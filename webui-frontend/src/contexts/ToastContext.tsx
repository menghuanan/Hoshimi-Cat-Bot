import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { ToastContext, type ToastContextValue, type ToastTone } from './toastContextValue'

type ToastItem = {
  id: number
  tone: ToastTone
  message: string
}

const toastIcons: Record<ToastTone, string> = {
  success: '✓',
  error: '!',
  warning: '!',
}

/**
 * 单个 provider 承载所有操作反馈，让保存、删除和提交结果不再散落在页面局部。
 */
export function ToastProvider({children}: {children: ReactNode}) {
  const [items, setItems] = useState<ToastItem[]>([])

  const showToast = useCallback((tone: ToastTone, message: string) => {
    const text = message.trim()
    if (!text) {
      return
    }
    const id = Date.now() + Math.random()
    setItems((current) => [...current, {id, tone, message: text}].slice(-4))
    window.setTimeout(() => {
      setItems((current) => current.filter((item) => item.id !== id))
    }, 3600)
  }, [])

  const value = useMemo<ToastContextValue>(() => ({showToast}), [showToast])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div data-toast-viewport="true" className="toast-viewport" aria-live="polite" aria-relevant="additions">
        {items.map((item) => (
          <div
            key={item.id}
            data-toast
            role={item.tone === 'error' ? 'alert' : 'status'}
            className={`toast toast-${item.tone}`}
          >
            <span className="toast-icon" aria-hidden="true">{toastIcons[item.tone]}</span>
            <span className="min-w-0 break-words">{item.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
