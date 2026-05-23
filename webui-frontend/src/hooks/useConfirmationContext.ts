import { useContext } from 'react'
import { ConfirmationContext } from '../contexts/confirmationContextValue'

/**
 * 普通确认和高风险确认都通过这个 hook 读取统一上下文对象。
 */
export function useConfirmationContext() {
  const context = useContext(ConfirmationContext)
  if (!context) {
    throw new Error('ConfirmationProvider is missing')
  }
  return context
}
