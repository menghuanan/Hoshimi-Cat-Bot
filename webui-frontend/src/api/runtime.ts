import { requestJson, type WebUiJsonRequestOptions } from './http'
import type { WebUiRuntimeSummary } from '../types/runtime'

/**
 * 运行态摘要只要一个 GET 入口，页面层不直接接触 fetch 细节。
 */
export async function fetchRuntimeSummary(
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiRuntimeSummary> {
  return requestJson<WebUiRuntimeSummary>('/api/runtime/summary', {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}
