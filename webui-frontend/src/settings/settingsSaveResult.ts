import type { WebUiSettingsSaveResult } from '../types/settings'

/**
 * 保存响应必须保留后端返回的具体原因，避免页面只显示泛化成功或失败。
 */
export function formatSaveResultMessage(results: Array<WebUiSettingsSaveResult | null>): string {
  const completed = results.filter((result): result is WebUiSettingsSaveResult => Boolean(result))
  if (completed.length === 0) {
    return '保存已取消'
  }
  const details = completed
    .flatMap((result) => {
      const messages = [
        ...(Array.isArray(result.validationErrors) ? result.validationErrors : []),
        result.message || '',
      ].map((message) => message.trim()).filter(Boolean)
      return messages.length > 0 ? messages : [result.success === false ? '后端拒绝保存' : '后端已持久化配置']
    })
  const uniqueDetails = Array.from(new Set(details)).join('；')
  if (completed.some((result) => result.success === false)) {
    return `保存失败：${uniqueDetails}`
  }
  return `保存成功：${uniqueDetails}`
}
