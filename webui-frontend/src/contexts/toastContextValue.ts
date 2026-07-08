import { createContext } from 'react'

export type ToastTone = 'success' | 'error' | 'warning'

export type ToastContextValue = {
  showToast: (tone: ToastTone, message: string) => void
}

/**
 * Toast 上下文只暴露发消息入口，避免页面直接管理全局通知队列。
 */
export const ToastContext = createContext<ToastContextValue | null>(null)
