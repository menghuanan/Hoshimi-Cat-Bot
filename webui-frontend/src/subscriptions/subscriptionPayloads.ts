import type { WebUiSubscriptionWritePayload } from '../types/subscriptions'

export type SubscriptionCreateInput = {
  type: string
  uid?: string
  targetGroup?: string
  bangumiId?: string
  groupName?: string
  groupUid?: string
  groupTarget?: string
  bangumiTarget?: string
  confirmationPassword: string
}

export type SubscriptionFilterPayload = WebUiSubscriptionWritePayload & {
  key: string
  kind: string
  mode: string
  content: string
  targetGroups?: string[]
}

export type SubscriptionTemplatePayload = WebUiSubscriptionWritePayload & {
  key: string
  type: string
  name: string
  content: string
  targetGroups?: string[]
}

export type SubscriptionAtAllPayload = WebUiSubscriptionWritePayload & {
  type: string
  targetGroups: string[]
}

export type SubscriptionThemePayload = WebUiSubscriptionWritePayload & {
  color: string
  targetGroups: string[]
}

export type SubscriptionTargetPayload = WebUiSubscriptionWritePayload & {
  targetGroup: string
}

export type SubscriptionUidPayload = WebUiSubscriptionWritePayload & {
  uid: string
}

export type SubscriptionRandomTemplatePayload = WebUiSubscriptionWritePayload & {
  enabled: boolean
}

/**
 * 订阅新增接口按当前类型拼装 payload，确认密码始终随请求一并提交。
 */
export function buildSubscriptionCreatePayload(input: SubscriptionCreateInput): Record<string, unknown> {
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
 * 删除订阅保留 itemId 便于前端测试和排查，后端高风险校验只读取确认密码。
 */
export function buildSubscriptionDeletePayload(itemId: string, confirmationPassword: string): WebUiSubscriptionWritePayload & {itemId: string} {
  return {
    itemId,
    confirmationPassword,
  }
}

/**
 * 过滤器保存 payload 覆盖新增和编辑场景，key 为空时由后端追加。
 */
export function buildSubscriptionFilterPayload(input: SubscriptionFilterPayload): SubscriptionFilterPayload {
  return {
    key: input.key,
    kind: input.kind,
    mode: input.mode,
    content: input.content,
    targetGroups: input.targetGroups || [],
    confirmationPassword: input.confirmationPassword,
  }
}

/**
 * 模板保存 payload 保留正文和模板类型，后端负责绑定到当前订阅作用域。
 */
export function buildSubscriptionTemplatePayload(input: SubscriptionTemplatePayload): SubscriptionTemplatePayload {
  return {
    key: input.key,
    type: input.type,
    name: input.name,
    content: input.content,
    targetGroups: input.targetGroups || [],
    confirmationPassword: input.confirmationPassword,
  }
}

/**
 * @全体保存 payload 只提交类型和目标群聊列表，避免页面层理解后端枚举映射。
 */
export function buildSubscriptionAtAllPayload(input: SubscriptionAtAllPayload): SubscriptionAtAllPayload {
  return {
    type: input.type,
    targetGroups: input.targetGroups,
    confirmationPassword: input.confirmationPassword,
  }
}

/**
 * 主题色写入允许空值恢复默认，单 UP 动态订阅通过 targetGroups 限定本次生效群聊。
 */
export function buildSubscriptionThemePayload(
  color: string,
  confirmationPassword: string,
  targetGroups: string[] = [],
): SubscriptionThemePayload {
  return {
    color,
    targetGroups,
    confirmationPassword,
  }
}

/**
 * 推送群聊保存 payload 保留原始输入，正整数和 subject 归一化由后端兜底校验。
 */
export function buildSubscriptionTargetPayload(input: SubscriptionTargetPayload): SubscriptionTargetPayload {
  return {
    targetGroup: input.targetGroup,
    confirmationPassword: input.confirmationPassword,
  }
}

/**
 * 分组订阅 ID 保存 payload 保留字符串形式，避免 UID 大整数转换或番剧前缀被破坏。
 */
export function buildSubscriptionUidPayload(input: SubscriptionUidPayload): SubscriptionUidPayload {
  return {
    uid: input.uid,
    confirmationPassword: input.confirmationPassword,
  }
}

/**
 * 随机模板开关 payload 保持布尔值，不在 API 层转换为字符串。
 */
export function buildSubscriptionRandomTemplatePayload(enabled: boolean, confirmationPassword: string): SubscriptionRandomTemplatePayload {
  return {
    enabled,
    confirmationPassword,
  }
}

/**
 * 嵌套配置删除接口只需要确认密码，资源定位统一放在 URL path 中。
 */
export function buildSubscriptionConfigDeletePayload(confirmationPassword: string): WebUiSubscriptionWritePayload {
  return {confirmationPassword}
}
