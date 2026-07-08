import { Shell } from './components/Shell'
import { ConfirmationProvider } from './contexts/ConfirmationContext'
import { ToastProvider } from './contexts/ToastContext'
import { WebUiNavigationProvider } from './contexts/WebUiNavigationContext'
import { useWebUiNavigation } from './hooks/useWebUiNavigation'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { LogsPage } from './pages/LogsPage'
import { SettingsPage } from './pages/SettingsPage'
import { SubscriptionsPage } from './pages/SubscriptionsPage'

/**
 * App 只负责路由分发和壳层装配，不把页面逻辑散到入口文件里。
 */
function AppContent() {
  const {page, navigate} = useWebUiNavigation()

  if (page === 'login') {
    return <LoginPage />
  }

  return (
    <Shell page={page} onNavigate={navigate}>
      {page === 'home' ? <DashboardPage /> : null}
      {page === 'settings' ? <SettingsPage /> : null}
      {page === 'subscriptions' ? <SubscriptionsPage /> : null}
      {page === 'logs' ? <LogsPage /> : null}
    </Shell>
  )
}

/**
 * 入口保留一个 provider，后续高风险确认和主题上下文都可以在这里继续堆叠。
 */
function App() {
  return (
    <ToastProvider>
      <ConfirmationProvider>
        <WebUiNavigationProvider>
          <AppContent />
        </WebUiNavigationProvider>
      </ConfirmationProvider>
    </ToastProvider>
  )
}

export default App
