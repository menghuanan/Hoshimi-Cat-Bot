import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { WebUiNavigationContext, type WebUiNavigationContextValue } from './webUiNavigationContextValue'
import { readWebUiPage, type WebUiPageName, writeWebUiPage } from '../router/webuiRouter'

/**
 * 全局导航状态集中在一个 context，方便 shell、页头和后续页面共享当前视图。
 */
export function WebUiNavigationProvider({children}: {children: ReactNode}) {
  const [page, setPage] = useState<WebUiPageName>(() => readWebUiPage())

  useEffect(() => {
    const handleRouteChange = () => {
      setPage(readWebUiPage())
    }
    window.addEventListener('hashchange', handleRouteChange)
    window.addEventListener('popstate', handleRouteChange)
    return () => {
      window.removeEventListener('hashchange', handleRouteChange)
      window.removeEventListener('popstate', handleRouteChange)
    }
  }, [])

  const value = useMemo<WebUiNavigationContextValue>(() => ({
    page,
    navigate: (nextPage) => {
      writeWebUiPage(nextPage)
      setPage(nextPage)
    },
  }), [page])

  return (
    <WebUiNavigationContext.Provider value={value}>
      {children}
    </WebUiNavigationContext.Provider>
  )
}
