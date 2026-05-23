import { createContext } from 'react'
import type { WebUiPageName } from '../router/webuiRouter'

export type WebUiNavigationContextValue = {
  page: WebUiPageName
  navigate: (page: WebUiPageName) => void
}

/**
 * 导航上下文值独立于 provider 组件，满足 React Refresh 对组件文件导出的限制。
 */
export const WebUiNavigationContext = createContext<WebUiNavigationContextValue | null>(null)
