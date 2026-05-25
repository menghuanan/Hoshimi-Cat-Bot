import { requestJson, type WebUiJsonRequestOptions } from './http'
import {
  buildSubscriptionEditorUrl,
  buildSubscriptionTemplateRandomUrl,
} from '../subscriptions/subscriptionEditors'
import {
  buildSubscriptionAtAllPayload,
  buildSubscriptionConfigDeletePayload,
  buildSubscriptionCreatePayload,
  buildSubscriptionDeletePayload,
  buildSubscriptionFilterPayload,
  buildSubscriptionRandomTemplatePayload,
  buildSubscriptionTemplatePayload,
  buildSubscriptionThemePayload,
  type SubscriptionAtAllPayload,
  type SubscriptionFilterPayload,
  type SubscriptionTemplatePayload,
} from '../subscriptions/subscriptionPayloads'

export {
  buildSubscriptionAtAllPayload,
  buildSubscriptionConfigDeletePayload,
  buildSubscriptionCreatePayload,
  buildSubscriptionDeletePayload,
  buildSubscriptionFilterPayload,
  buildSubscriptionRandomTemplatePayload,
  buildSubscriptionTemplatePayload,
  buildSubscriptionThemePayload,
}

/**
 * 订阅列表加载保持 GET 语义，页面层只关心后端快照。
 */
export async function listSubscriptions(options: WebUiJsonRequestOptions = {}): Promise<unknown> {
  return requestJson('/api/subscriptions', {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * 订阅新增继续使用统一的 JSON 请求入口，便于保留认证头和错误处理。
 */
export async function createSubscription(
  payload: Record<string, unknown>,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson('/api/subscriptions', {
    ...options,
    method: 'POST',
    body: payload,
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 订阅删除同样复用统一 JSON 请求入口，避免页面各自拼装 DELETE body。
 */
export async function deleteSubscription(
  itemId: string,
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(`/api/subscriptions/${encodeURIComponent(itemId)}`, {
    ...options,
    method: 'DELETE',
    body: buildSubscriptionDeletePayload(itemId, confirmationPassword),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 过滤器列表读取沿用后端只读端点，GET 请求不附带 JSON body。
 */
export async function listSubscriptionFilters(
  itemId: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'filters'), {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * 过滤器保存通过统一 payload builder 写入，确保确认密码不会被调用方遗漏。
 */
export async function saveSubscriptionFilter(
  itemId: string,
  payload: SubscriptionFilterPayload,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'filters'), {
    ...options,
    method: 'POST',
    body: buildSubscriptionFilterPayload(payload),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 过滤器删除把 key 放在路径中，body 只承载高风险确认密码。
 */
export async function deleteSubscriptionFilter(
  itemId: string,
  key: string,
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'filters', key), {
    ...options,
    method: 'DELETE',
    body: buildSubscriptionConfigDeletePayload(confirmationPassword),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 模板列表读取包含模板数组和随机模板开关状态。
 */
export async function listSubscriptionTemplates(
  itemId: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'templates'), {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * 模板保存 payload 保持 key/type/name/content 与后端 DTO 对齐。
 */
export async function saveSubscriptionTemplate(
  itemId: string,
  payload: SubscriptionTemplatePayload,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'templates'), {
    ...options,
    method: 'POST',
    body: buildSubscriptionTemplatePayload(payload),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 模板删除复用统一嵌套配置删除 body，避免泄漏额外业务字段。
 */
export async function deleteSubscriptionTemplate(
  itemId: string,
  key: string,
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'templates', key), {
    ...options,
    method: 'DELETE',
    body: buildSubscriptionConfigDeletePayload(confirmationPassword),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 随机模板开关写入独立端点，避免页面层直接拼接 `/random` 后缀。
 */
export async function setSubscriptionTemplateRandom(
  itemId: string,
  enabled: boolean,
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionTemplateRandomUrl(itemId), {
    ...options,
    method: 'POST',
    body: buildSubscriptionRandomTemplatePayload(enabled, confirmationPassword),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * @全体列表读取当前订阅可编辑的聚合记录。
 */
export async function listSubscriptionAtAll(
  itemId: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'atall'), {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * @全体保存提交类型和目标群聊，确认密码仍由调用方确认后传入。
 */
export async function saveSubscriptionAtAll(
  itemId: string,
  payload: SubscriptionAtAllPayload,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'atall'), {
    ...options,
    method: 'POST',
    body: buildSubscriptionAtAllPayload(payload),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * @全体删除按聚合 key 定位，body 保持高风险确认最小形状。
 */
export async function deleteSubscriptionAtAll(
  itemId: string,
  key: string,
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'atall', key), {
    ...options,
    method: 'DELETE',
    body: buildSubscriptionConfigDeletePayload(confirmationPassword),
    includeJson: true,
    authenticated: false,
  })
}

/**
 * 主题色读取只返回当前颜色，页面决定如何展示空值。
 */
export async function readSubscriptionTheme(
  itemId: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'theme'), {
    ...options,
    method: 'GET',
    authenticated: true,
    includeJson: false,
  })
}

/**
 * 主题色保存使用单独 builder，保持颜色字段名与后端 DTO 一致。
 */
export async function saveSubscriptionTheme(
  itemId: string,
  color: string,
  targetGroups: string[],
  confirmationPassword: string,
  options: WebUiJsonRequestOptions = {},
): Promise<unknown> {
  return requestJson(buildSubscriptionEditorUrl(itemId, 'theme'), {
    ...options,
    method: 'POST',
    body: buildSubscriptionThemePayload(color, confirmationPassword, targetGroups),
    includeJson: true,
    authenticated: false,
  })
}
