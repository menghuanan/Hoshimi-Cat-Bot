export type SubscriptionCreateInput = {
  type: string
  uid?: string
  targetGroup?: string
  bangumiId?: string
  groupName?: string
  groupUid?: string
  groupTarget?: string
  bangumiTarget?: string
}

export type SubscriptionFilterPayload = {
  key: string
  kind: string
  mode: string
  content: string
  targetGroups?: string[]
}

export type SubscriptionTemplatePayload = {
  key: string
  type: string
  name: string
  content: string
  targetGroups?: string[]
}

export type SubscriptionAtAllPayload = {
  type: string
  targetGroups: string[]
}

export type SubscriptionThemePayload = {
  color: string
  targetGroups: string[]
}

export type SubscriptionTargetPayload = {
  targetGroup: string
}

export type SubscriptionUidPayload = {
  uid: string
}

export type SubscriptionRandomTemplatePayload = {
  enabled: boolean
}

/**
 * 订阅新增接口按当前类型拼装 payload，只提交业务字段。
 */
export function buildSubscriptionCreatePayload(input: SubscriptionCreateInput): Record<string, unknown> {
  if (input.type === 'group') {
    return {
      type: input.type,
      groupName: input.groupName || '',
      uid: input.groupUid || input.uid || '',
      targetGroup: input.groupTarget || input.targetGroup || '',
    }
  }
  if (input.type === 'bangumi') {
    return {
      type: input.type,
      bangumiId: input.bangumiId || '',
      targetGroup: input.bangumiTarget || input.targetGroup || '',
    }
  }
  return {
    type: input.type,
    uid: input.uid || '',
    targetGroup: input.targetGroup || '',
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
  }
}

/**
 * @全体保存 payload 只提交类型和目标群聊列表，避免页面层理解后端枚举映射。
 */
export function buildSubscriptionAtAllPayload(input: SubscriptionAtAllPayload): SubscriptionAtAllPayload {
  return {
    type: input.type,
    targetGroups: input.targetGroups,
  }
}

/**
 * 主题色写入允许空值恢复默认，单 UP 动态订阅通过 targetGroups 限定本次生效群聊。
 */
export function buildSubscriptionThemePayload(
  color: string,
  targetGroups: string[] = [],
): SubscriptionThemePayload {
  return {
    color,
    targetGroups,
  }
}

/**
 * 推送群聊保存 payload 保留原始输入，正整数和 subject 归一化由后端兜底校验。
 */
export function buildSubscriptionTargetPayload(input: SubscriptionTargetPayload): SubscriptionTargetPayload {
  return {
    targetGroup: input.targetGroup,
  }
}

/**
 * 分组订阅 ID 保存 payload 保留字符串形式，避免 UID 大整数转换或番剧前缀被破坏。
 */
export function buildSubscriptionUidPayload(input: SubscriptionUidPayload): SubscriptionUidPayload {
  return {
    uid: input.uid,
  }
}

/**
 * 随机模板开关 payload 保持布尔值，不在 API 层转换为字符串。
 */
export function buildSubscriptionRandomTemplatePayload(enabled: boolean): SubscriptionRandomTemplatePayload {
  return {
    enabled,
  }
}
