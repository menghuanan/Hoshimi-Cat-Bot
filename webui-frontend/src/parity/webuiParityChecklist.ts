export type WebUiParityGroup = {
  id: 'dashboard' | 'settings' | 'subscriptions' | 'logs' | 'auth-shell-security'
  items: string[]
}

/**
 * React 迁移等价清单只保存稳定能力 ID，供测试和人工验收对齐范围。
 */
export const webuiParityChecklist: WebUiParityGroup[] = [
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
    id: 'auth-shell-security',
    items: [
      'login',
      'forced-password-change',
      'logout',
      'admin-menu',
      'theme-preference',
      'path-hash-navigation',
      'unauthorized-login-redirect',
      'confirmation-password-writes',
      'no-native-browser-dialogs',
      'api-route-boundaries',
    ],
  },
]
