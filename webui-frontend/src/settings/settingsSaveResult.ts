import type { WebUiSettingsSaveResult } from '../types/settings'
import { normalizeVisibleMessage } from '../utils/errorMessages'

/**
 * 保存响应必须保留后端返回的具体原因，避免页面只显示泛化成功或失败。
 */
export function formatSaveResultMessage(results: Array<WebUiSettingsSaveResult | null>): string {
  const completed = results.filter((result): result is WebUiSettingsSaveResult => Boolean(result))
  if (completed.length === 0) {
    return '保存已取消'
  }
  if (completed.some((result) => result.success === false)) {
    const details = completed.flatMap((result) => {
      const messages = [
        ...(Array.isArray(result.validationErrors) ? result.validationErrors : []),
        result.message || '',
      ]
        .map((message) => normalizeSaveFailureMessage(String(message || '')))
        .filter(Boolean)
      return messages.length > 0 ? messages : ['请检查填写内容后重试']
    })
    const uniqueDetails = Array.from(new Set(details)).join('；')
    return `保存失败：${uniqueDetails || '请检查填写内容后重试'}`
  }
  return '保存成功'
}

/**
 * 保存失败详情只保留一次重试提醒，避免 fallback 和统一提示叠加成重复句子。
 */
function normalizeSaveFailureMessage(message: string): string {
  const normalized = normalizeVisibleMessage(message, '请检查填写内容后重试')
  return normalized.endsWith('，请稍后重试')
    ? normalized.slice(0, -'，请稍后重试'.length)
    : normalized
}
