export type WebUiParityGroup = {
  id: 'login' | 'shell' | 'dashboard' | 'settings' | 'subscriptions' | 'logs' | 'security' | 'packaging'
  items: string[]
}

/**
 * React 迁移等价清单只保存稳定能力 ID，供测试和人工验收对齐范围。
 */
export const webuiParityChecklist: WebUiParityGroup[] = [
  {
    id: 'login',
    items: [
      'wrong-password-failure',
      'successful-login',
      'forced-password-change',
      'session-refresh',
      'logout-clears-session',
      'unauthorized-login-redirect',
    ],
  },
  {
    id: 'shell',
    items: [
      'direct-root-route',
      'direct-login-route',
      'direct-settings-route',
      'direct-subscriptions-route',
      'direct-logs-route',
      'refresh-preserves-page',
      'browser-history-sync',
      'mobile-navigation',
      'theme-preference',
    ],
  },
  {
    id: 'dashboard',
    items: [
      'runtime-version',
      'runtime-start-time',
      'runtime-uptime',
      'system-time',
      'system-load',
      'cpu-memory-storage',
      'docker-status',
      'bili-account-summary',
      'platform-connection-summary',
      'daily-push-count',
      'recent-push-records',
      'dashboard-shortcuts',
    ],
  },
  {
    id: 'settings',
    items: [
      'integration-category',
      'feature-category',
      'bili-account-category',
      'polling-category',
      'render-category',
      'message-category',
      'admin-category',
      'translate-category',
      'snapshot-token-save',
      'sensitive-inputs-empty',
      'proxy-write-only-preserve-replace',
    ],
  },
  {
    id: 'subscriptions',
    items: [
      'subscription-type-filter',
      'subscription-search',
      'create-delete-confirmation',
      'dynamic-bangumi-group-modes',
      'multi-target-editing',
      'filter-editor',
      'template-editor',
      'at-all-editor',
      'theme-color-editor',
      'random-template-toggle',
    ],
  },
  {
    id: 'logs',
    items: [
      'log-source-selection',
      'log-level-filter',
      'log-module-filter',
      'log-keyword-search',
      'log-auto-refresh',
      'log-manual-refresh',
      'log-export',
      'log-clear-confirmation',
    ],
  },
  {
    id: 'security',
    items: [
      'confirmation-password-writes',
      'no-native-browser-dialogs',
      'api-route-boundaries',
      'masked-sensitive-readback',
      'native-notification-absent',
    ],
  },
  {
    id: 'packaging',
    items: [
      'react-bundled-shell',
      'react-bundled-assets',
      'plain-runtime-deleted',
      'process-resources-react-only',
      'assets-route-serves-react-assets',
    ],
  },
]
