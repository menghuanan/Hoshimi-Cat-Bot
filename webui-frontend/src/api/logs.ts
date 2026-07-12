import { requestJson, type WebUiJsonRequestOptions } from './http'

/**
 * 日志来源列表只读加载，供日志页下拉框和轮询入口共享。
 */
export async function listLogSources(options: WebUiJsonRequestOptions = {}): Promise<unknown> {
  return requestJson('/api/logs/sources', {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * 日志内容按 sourceId 拉取固定 tail，保持与旧脚本一致的查询形态。
 */
export async function readLogWindow(
  sourceId: string,
  tailLines: number,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(`/api/logs/${encodeURIComponent(sourceId)}?tail=${tailLines}`, {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}
