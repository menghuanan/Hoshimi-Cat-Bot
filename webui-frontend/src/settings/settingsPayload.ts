import { settingsFieldByKey, type SettingsFileId } from './settingsSchema'

export type BuildSettingsSavePayloadInput = {
  file: SettingsFileId
  snapshotToken: string
  confirmationPassword: string
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
      payload.proxyUpdateMode = proxies.length > 0 ? 'replace' : 'preserve'
      return
    }
    if (field.writeOnly && String(value || '').trim() === '') {
      return
    }
    payload[field.payloadKey || key] = coerceSettingsValue(field.type, value)
  })

  if (input.file === 'biliConfig' && !('proxyUpdateMode' in payload)) {
    payload.proxies = []
    payload.proxyUpdateMode = 'preserve'
  }

  return payload
}
