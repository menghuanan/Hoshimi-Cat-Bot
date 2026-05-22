import { describe, expect, it } from 'vitest'
import { webuiParityChecklist } from './webuiParityChecklist'

describe('webui parity checklist', () => {
  /**
   * 固定等价清单的顶层分组，避免后续迁移遗漏页面或安全约束。
   */
  it('tracks the required page and security parity groups', () => {
    expect(webuiParityChecklist.map((group) => group.id)).toEqual([
      'dashboard',
      'settings',
      'subscriptions',
      'logs',
      'auth-shell-security',
    ])
    expect(webuiParityChecklist.flatMap((group) => group.items)).toContain('proxy-write-only-preserve-replace')
    expect(webuiParityChecklist.flatMap((group) => group.items)).toContain('no-native-browser-dialogs')
  })
})
