import { useContext } from 'react'
import { ToastContext } from '../contexts/toastContextValue'

/**
 * Toast hook 要求调用方位于 ToastProvider 内，避免静默丢失操作反馈。
 */
export function useToast() {
  const value = useContext(ToastContext)
  if (!value) {
    throw new Error('ToastContext is missing')
  }
  return value
}
