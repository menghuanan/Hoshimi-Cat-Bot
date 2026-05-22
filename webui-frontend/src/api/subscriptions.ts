import { requestJson, type WebUiJsonRequestOptions } from './http'
import type { WebUiSubscriptionWritePayload } from '../types/subscriptions'

/**
 * 订阅新增接口按当前类型拼装 payload，确认密码始终随请求一并提交。
 */
export function buildSubscriptionCreatePayload(input: {
  type: string
  uid?: string
  targetGroup?: string
  bangumiId?: string
  groupName?: string
  groupUid?: string
  groupTarget?: string
  bangumiTarget?: string
  confirmationPassword: string
}): Record<string, unknown> {
  if (input.type === 'group') {
    return {
      type: input.type,
      groupName: input.groupName || '',
      uid: input.groupUid || input.uid || '',
      targetGroup: input.groupTarget || input.targetGroup || '',
      confirmationPassword: input.confirmationPassword,
    }
  }
  if (input.type === 'bangumi') {
    return {
      type: input.type,
      bangumiId: input.bangumiId || '',
      targetGroup: input.bangumiTarget || input.targetGroup || '',
      confirmationPassword: input.confirmationPassword,
    }
  }
  return {
    type: input.type,
    uid: input.uid || '',
    targetGroup: input.targetGroup || '',
    confirmationPassword: input.confirmationPassword,
  }
}

/**
 * 删除订阅只需要 itemId 和确认密码，后端再决定具体删除哪条写入记录。
 */
export function buildSubscriptionDeletePayload(itemId: string, confirmationPassword: string): WebUiSubscriptionWritePayload & {itemId: string} {
  return {
    itemId,
    confirmationPassword,
  }
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
    authenticated: true,
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
    authenticated: true,
  })
}
