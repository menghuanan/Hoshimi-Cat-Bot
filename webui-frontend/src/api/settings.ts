import { requestJson, type WebUiJsonRequestOptions } from './http'
import type { WebUiSettingsSaveResult } from '../types/settings'

/**
 * BiliConfig 保存输入只保留 Task 2 需要的 proxy 和确认密码语义，其他字段后续页面再补齐。
 */
export type WebUiBiliConfigSaveInput = {
  snapshotToken: string
  confirmationPassword: string
  proxyText?: string
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
 * 代理输入为空时保持 preserve，有明确文本时才替换为新列表。
 */
export function buildBiliConfigSavePayload(input: WebUiBiliConfigSaveInput): Record<string, unknown> {
  const proxies = String(input.proxyText || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
  return {
    snapshotToken: input.snapshotToken,
    confirmationPassword: input.confirmationPassword,
    proxies,
    proxyUpdateMode: proxies.length > 0 ? 'replace' : 'preserve',
    ...(input.fields || {}),
  }
}

/**
 * bot.yml 保存 payload 只补当前需要的 token 字段，其余字段由页面后续扩展。
 */
export function buildBotConfigSavePayload(input: WebUiBotConfigSaveInput): Record<string, unknown> {
  return {
    snapshotToken: input.snapshotToken,
    confirmationPassword: input.confirmationPassword,
    oneBot11Token: input.token || '',
    ...(input.fields || {}),
  }
}

/**
 * 配置保存仍然走 JSON POST，并保留 snapshotToken 作为并发写保护。
 */
export async function saveBiliConfig(
  input: WebUiBiliConfigSaveInput,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiSettingsSaveResult> {
  return requestJson<WebUiSettingsSaveResult>('/api/config/bili-config', {
    ...options,
    method: 'POST',
    body: buildBiliConfigSavePayload(input),
    includeJson: true,
    authenticated: true,
  })
}

/**
 * bot.yml 保存同样复用统一请求入口，避免与 BiliConfig 分叉出第二套错误处理。
 */
export async function saveBotConfig(
  input: WebUiBotConfigSaveInput,
  options: WebUiJsonRequestOptions = {},
): Promise<WebUiSettingsSaveResult> {
  return requestJson<WebUiSettingsSaveResult>('/api/config/bot', {
    ...options,
    method: 'POST',
    body: buildBotConfigSavePayload(input),
    includeJson: true,
    authenticated: true,
  })
}
