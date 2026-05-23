import { describe, expect, it } from 'vitest'
import { formatLoginErrorMessage, formatPasswordErrorMessage, normalizeVisibleMessage } from './errorMessages'

describe('errorMessages', () => {
  it('normalizes english and HTTP failures to Chinese fallback text', () => {
    expect(normalizeVisibleMessage(new Error('oneBot11Port is invalid'), '保存失败')).toBe('保存失败，请稍后重试')
    expect(normalizeVisibleMessage(new Error('HTTP 500'), '保存失败')).toBe('保存失败，请稍后重试')
  })

  it('keeps existing Chinese prompts and session-expired messages intact', () => {
    expect(normalizeVisibleMessage(new Error('配置已变化，请刷新后重试'), '保存失败')).toBe('配置已变化，请刷新后重试')
    expect(normalizeVisibleMessage(new Error('请重新登录'), '保存失败')).toBe('请重新登录')
  })

  it('formats login and password errors without leaking HTTP status codes', () => {
    expect(formatLoginErrorMessage(new Error('HTTP 401'))).toBe('密码错误，请重试')
    expect(formatPasswordErrorMessage(new Error('HTTP 403'), '保存失败')).toBe('密码错误')
    expect(formatPasswordErrorMessage(new Error('密码错误，请重试'), '保存失败')).toBe('密码错误')
  })
})
