import { requestJson, type WebUiJsonRequestOptions } from './http'
import type { WebUiConfigHotReloadJob } from '../types/settings'
import { buildSettingsSavePayload } from '../settings/settingsPayload'

/**
 * BiliConfig 保存输入只保留 Task 2 需要的 proxy 和确认密码语义，其他字段后续页面再补齐。
 */
export type WebUiBiliConfigSaveInput = {
  snapshotToken: string
  confirmationPassword: string
  proxyText?: string
  proxyUpdateMode?: 'preserve' | 'replace' | 'clear'
  currentProxies?: string[]
  fields?: Record<string, unknown>
}

/**
 * bot.yml 保存输入只保留当前 task 需要的 token 和确认密码语义。
 */
export type WebUiBotConfigSaveInput = {
  snapshotToken: string
  confirmationPassword: string
  token?: string
  fields?: Record<string, unknown>
}

export type WebUiBiliDataSaveInput = {
  snapshotToken: string
  confirmationPassword: string
  fields?: Record<string, unknown>
}

export type WebUiSettingsBatchSaveInput = {
  biliConfig?: Record<string, unknown>
  biliData?: Record<string, unknown>
  botConfig?: Record<string, unknown>
}

/**
 * 配置加载接口复用统一的 JSON GET，页面层只拿到后端快照对象。
 */
export async function loadBiliConfig(options: WebUiJsonRequestOptions = {}): Promise<Record<string, unknown>> {
  return requestJson<Record<string, unknown>>('/api/config/bili-config', {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * bot.yml 加载同样通过统一入口，避免页面层手动补鉴权头。
 */
export async function loadBotConfig(options: WebUiJsonRequestOptions = {}): Promise<Record<string, unknown>> {
  return requestJson<Record<string, unknown>>('/api/config/bot', {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * BiliData 加载进入设置页同一快照模型，当前主要承载链接解析黑名单。
 */
export async function loadBiliData(options: WebUiJsonRequestOptions = {}): Promise<Record<string, unknown>> {
  return requestJson<Record<string, unknown>>('/api/config/bili-data', {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * 代理输入默认空值保留、有文本替换；显式 clear 模式用于清空现有代理。
 */
export function buildBiliConfigSavePayload(input: WebUiBiliConfigSaveInput): Record<string, unknown> {
  return buildSettingsSavePayload({
    file: 'biliConfig',
    snapshotToken: input.snapshotToken,
    confirmationPassword: input.confirmationPassword,
    proxyUpdateMode: input.proxyUpdateMode,
    values: {
      'proxyConfig.proxy': input.proxyText || '',
      ...(input.fields || {}),
    },
  })
}

/**
 * bot.yml 保存 payload 只补当前需要的 token 字段，其余字段由页面后续扩展。
 */
export function buildBotConfigSavePayload(input: WebUiBotConfigSaveInput): Record<string, unknown> {
  return buildSettingsSavePayload({
    file: 'botConfig',
    snapshotToken: input.snapshotToken,
    confirmationPassword: input.confirmationPassword,
    values: {
      'platform.onebot11.token': input.token || '',
      ...(input.fields || {}),
    },
  })
}

/**
 * BiliData payload 由字段元数据转换，避免前端直接拼后端持久化结构。
 */
export function buildBiliDataSavePayload(input: WebUiBiliDataSaveInput): Record<string, unknown> {
  return buildSettingsSavePayload({
    file: 'biliData',
    snapshotToken: input.snapshotToken,
    confirmationPassword: input.confirmationPassword,
    values: input.fields || {},
  })
}

/**
 * 配置保存仍然走 JSON POST，并保留 snapshotToken 作为并发写保护。
 */
export async function saveBiliConfig(
  input: WebUiBiliConfigSaveInput,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiConfigHotReloadJob> {
  return requestJson<WebUiConfigHotReloadJob>('/api/config/bili-config', {
    ...options,
    method: 'POST',
    body: buildBiliConfigSavePayload(input),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * bot.yml 保存同样复用统一请求入口，避免与 BiliConfig 分叉出第二套错误处理。
 */
export async function saveBotConfig(
  input: WebUiBotConfigSaveInput,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiConfigHotReloadJob> {
  return requestJson<WebUiConfigHotReloadJob>('/api/config/bot', {
    ...options,
    method: 'POST',
    body: buildBotConfigSavePayload(input),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 设置页一次点击使用批量保存接口，确保后端只创建一个热重载任务。
 */
export async function saveSettingsBatch(
  body: WebUiSettingsBatchSaveInput,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiConfigHotReloadJob> {
  return requestJson<WebUiConfigHotReloadJob>('/api/config/save-batch', {
    ...options,
    method: 'POST',
    body,
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 保存任务状态轮询只读取后端 DTO，不在前端推断生命周期。
 */
export async function loadSettingsSaveJob(
  jobId: string,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiConfigHotReloadJob> {
  return requestJson<WebUiConfigHotReloadJob>(`/api/config/save-jobs/${encodeURIComponent(jobId)}`, {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}
