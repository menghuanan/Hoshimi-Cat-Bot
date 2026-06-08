import { settingsFieldByKey, type SettingsFileId } from './settingsSchema'

export type BuildSettingsSavePayloadInput = {
  file: SettingsFileId
  snapshotToken: string
  confirmationPassword: string
  proxyUpdateMode?: 'preserve' | 'replace' | 'clear'
  values?: Record<string, unknown>
}

/**
 * 多行配置输入按非空行提交，代理列表和后续列表字段复用同一规则。
 */
function readLines(value: unknown): string[] {
  return String(value || '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
}

/**
 * 表单值按字段类型转换成后端 DTO 需要的基本类型。
 */
function coerceSettingsValue(type: string, value: unknown): unknown {
  if (type === 'boolean') {
    return value === true || value === 'true'
  }
  if (type === 'number') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  return String(value ?? '')
}

/**
 * 布尔文本兼容 checkbox、select 和后端快照里的字符串表现。
 */
function coerceSettingsBool(value: unknown): boolean {
  return value === true || String(value).toLowerCase() === 'true' || String(value) === '1'
}

/**
 * 管理员 QQ 在页面展示为数字，提交时转换成平台联系人 subject。
 */
function adminContactFromQQ(value: unknown): string {
  const text = String(value || '').trim()
  return text ? `onebot11:private:${text}` : ''
}

/**
 * 徽章聚合选项在提交时拆回后端已有的 left/right 两个布尔字段。
 */
function badgeBooleansFromChoice(value: unknown): {leftBadgeEnable: boolean, rightBadgeEnable: boolean} {
  const choice = String(value || '').trim()
  return {
    leftBadgeEnable: choice === 'left' || choice === 'both',
    rightBadgeEnable: choice === 'right' || choice === 'both',
  }
}

/**
 * 群普通管理员保持旧 WebUI 的逐行编辑体验，保存前转成 bot.yml DTO 结构。
 */
function parseAdminLines(value: unknown): Array<Record<string, unknown>> {
  const grouped = new Map<number, Set<number>>()
  String(value || '').split(/\r?\n/).forEach((line, index) => {
    const text = line.trim()
    if (!text) return
    const matched = text.match(/^(\d+)\s*[:：]\s*(\d+)$/)
    if (!matched) {
      throw new Error(`第 ${index + 1} 行格式应为 群号:QQ号`)
    }
    const groupId = Number.parseInt(matched[1], 10)
    const userId = Number.parseInt(matched[2], 10)
    if (!grouped.has(groupId)) {
      grouped.set(groupId, new Set())
    }
    grouped.get(groupId)?.add(userId)
  })
  return Array.from(grouped.entries()).map(([groupId, userIds]) => {
    const sortedUserIds = Array.from(userIds)
    return {
      groupId,
      userIds: sortedUserIds,
      groupContact: `onebot11:group:${groupId}`,
      userContacts: sortedUserIds.map((userId) => `onebot11:private:${userId}`),
    }
  })
}

/**
 * JSON 列表高级字段缺失或损坏时按空列表提交，让后端继续执行最终校验。
 */
function parseJsonList(value: unknown): unknown[] {
  try {
    const parsed = JSON.parse(String(value || '[]'))
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

/**
 * 设置保存 payload 从字段元数据生成，敏感写入字段为空时保留后端现值。
 */
export function buildSettingsSavePayload(input: BuildSettingsSavePayloadInput): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    snapshotToken: input.snapshotToken,
    confirmationPassword: input.confirmationPassword,
  }

  Object.entries(input.values || {}).forEach(([key, value]) => {
    const field = settingsFieldByKey.get(key)
    if (!field || field.file !== input.file) {
      return
    }
    if (key === 'proxyConfig.proxy') {
      const proxies = readLines(value)
      payload.proxies = proxies
      payload.proxyUpdateMode = input.proxyUpdateMode || (proxies.length > 0 ? 'replace' : 'preserve')
      return
    }
    if (input.file === 'biliData' && key === 'linkParseBlacklistContacts') {
      // BiliData 的链接解析黑名单使用逐行联系人 subject，提交前归一成数组交给后端校验。
      payload.linkParseBlacklistContacts = readLines(value)
      return
    }
    if (key === 'imageConfig.badgeEnable.choice') {
      Object.assign(payload, badgeBooleansFromChoice(value))
      return
    }
    if (key.startsWith('cacheConfig.expires.')) {
      const cacheKey = key.slice('cacheConfig.expires.'.length)
      payload.cacheExpires = {
        ...((payload.cacheExpires as Record<string, number> | undefined) || {}),
        [cacheKey]: Number(coerceSettingsValue('number', value)),
      }
      return
    }
    if (key === 'adminContactQQ') {
      payload.adminContact = adminContactFromQQ(value)
      payload.admin = Number(coerceSettingsValue('number', value))
      return
    }
    if (key === 'adminsText') {
      payload.admins = parseAdminLines(value)
      return
    }
    if (key === 'targets' || key === 'admins') {
      payload[field.payloadKey || key] = parseJsonList(value)
      return
    }
    if (field.writeOnly && String(value || '').trim() === '') {
      return
    }
    payload[field.payloadKey || key] = field.type === 'boolean' ? coerceSettingsBool(value) : coerceSettingsValue(field.type, value)
  })

  if (input.file === 'biliConfig' && !('proxyUpdateMode' in payload)) {
    payload.proxies = []
    payload.proxyUpdateMode = input.proxyUpdateMode || 'preserve'
  }

  return payload
}
