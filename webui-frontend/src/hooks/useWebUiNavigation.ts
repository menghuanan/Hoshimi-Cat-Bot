import { useContext } from 'react'
import { WebUiNavigationContext } from '../contexts/webUiNavigationContextValue'

/**
 * 页面层只通过这个 hook 读取和切换导航，不直接碰 hash 或 pathname。
 */
export function useWebUiNavigation() {
  const context = useContext(WebUiNavigationContext)
  if (!context) {
    throw new Error('WebUiNavigationProvider is missing')
  }
  return context
}
