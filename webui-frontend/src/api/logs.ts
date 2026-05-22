import { requestJson, type WebUiJsonRequestOptions } from './http'
import type { WebUiLogClearPayload } from '../types/logs'

/**
 * 清空日志的 payload 只保留 sourceId 和确认密码，和当前后端路由约定一致。
 */
export function buildLogClearPayload(sourceId: string, confirmationPassword: string): WebUiLogClearPayload {
  return {sourceId, confirmationPassword}
}

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
 * 日志窗口按 sourceId 拉取固定 tail，保持与旧脚本一致的查询形态。
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

/**
 * 日志来源清空仍然走统一 JSON POST，确保鉴权和错误处理与其他写路径一致。
 */
export async function clearLogSource(
  sourceId: string,
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(`/api/logs/${encodeURIComponent(sourceId)}/clear`, {
    ...options,
    method: 'POST',
    body: buildLogClearPayload(sourceId, confirmationPassword),
    includeJson: true,
    authenticated: true,
  })
}
