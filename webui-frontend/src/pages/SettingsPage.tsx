import { useMemo, useState } from 'react'
import { PageSection } from '../components/PageSection'
import { SettingsField } from '../components/settings/SettingsField'
import { SettingsTabs } from '../components/settings/SettingsTabs'
import { useSettingsFiles } from '../hooks/useSettingsFiles'
import { settingsCategories, validateSettingsValues, type SettingsCategoryId, type SettingsFieldDefinition } from '../settings/settingsSchema'

type SettingsFieldSnapshot = {
  key?: string
  value?: string
}

type SettingsFormValues = Record<string, string | boolean>

/**
 * 配置页按元数据渲染八个分区，敏感字段只允许空输入触发保留语义。
 */
export function SettingsPage() {
  const {loading, biliConfig, botConfig, saveBili, saveBot, reload} = useSettingsFiles()
  const [activeCategoryId, setActiveCategoryId] = useState<SettingsCategoryId>('integration')
  const [editedValues, setEditedValues] = useState<SettingsFormValues>({})
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const activeCategory = settingsCategories.find((category) => category.id === activeCategoryId) || settingsCategories[0]
  const allSettingsFields = useMemo(() => settingsCategories.flatMap((category) => category.fields), [])

  const fieldValues = useMemo(() => ({
    biliConfig: readFieldValues(biliConfig),
    botConfig: readFieldValues(botConfig),
  }), [biliConfig, botConfig])

  const initialValues = useMemo<SettingsFormValues>(() => {
    // 初始值从快照派生，写入专用字段保持空值不回显。
    return Object.fromEntries(activeCategory.fields.map((field) => [
      field.key,
      readInitialFieldValue(field, fieldValues),
    ]))
  }, [activeCategory, fieldValues])
  const completeValues = useMemo<SettingsFormValues>(() => {
    // 完整值用于组装文件级 DTO，避免只保存当前表单时把同文件其它字段写成后端默认值。
    return {
      ...Object.fromEntries(allSettingsFields.map((field) => [
        field.key,
        readInitialFieldValue(field, fieldValues),
      ])),
      ...editedValues,
    }
  }, [allSettingsFields, editedValues, fieldValues])
  // 当前表单值由快照初始值和用户编辑覆盖组成，避免 effect 中同步 setState。
  const values = useMemo<SettingsFormValues>(() => ({
    ...initialValues,
    ...editedValues,
  }), [editedValues, initialValues])

  /**
   * 字段变更只更新当前表单值，保存时再按文件边界拆分提交。
   */
  const updateValue = (key: string, value: string | boolean) => {
    setEditedValues((current) => ({...current, [key]: value}))
  }

  /**
   * 切换设置分区时丢弃未提交的本地编辑，下一屏重新使用当前快照派生值。
   */
  const selectCategory = (categoryId: SettingsCategoryId) => {
    setEditedValues({})
    setActiveCategoryId(categoryId)
  }

  /**
   * 保存当前分区，BiliConfig 和 bot.yml 分别带自己的 snapshotToken。
   */
  const saveActiveCategory = async () => {
    setSaving(true)
    setMessage('')
    try {
      const biliValues = pickValuesForFile(activeCategory.fields, values, 'biliConfig')
      const botValues = pickValuesForFile(activeCategory.fields, values, 'botConfig')
      const validationErrors = validateSettingsValues({...biliValues, ...botValues})
      if (validationErrors.length > 0) {
        setMessage(validationErrors.join('；'))
        return
      }
      const completeBiliValues = pickValuesForFile(allSettingsFields, completeValues, 'biliConfig')
      const completeBotValues = pickValuesForFile(allSettingsFields, completeValues, 'botConfig')
      const biliToken = String(biliConfig?.snapshotToken || '')
      const botToken = String(botConfig?.snapshotToken || '')
      if (biliToken && Object.keys(biliValues).length > 0) {
        await saveBili({
          snapshotToken: biliToken,
          proxyText: String(completeBiliValues['proxyConfig.proxy'] || ''),
          fields: omitKey(completeBiliValues, 'proxyConfig.proxy'),
        })
      }
      if (botToken && Object.keys(botValues).length > 0) {
        await saveBot({
          snapshotToken: botToken,
          token: String(completeBotValues['platform.onebot11.token'] || ''),
          fields: omitKey(completeBotValues, 'platform.onebot11.token'),
        })
      }
      await reload()
      setEditedValues({})
      setMessage('配置已提交')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div data-page="settings" className="space-y-6">
      <PageSection
        title="写入设置"
        actions={(
          <button
            type="button"
            className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
            disabled={saving || loading}
            onClick={saveActiveCategory}
          >
            {saving ? '保存中' : '保存'}
          </button>
        )}
      >
        <SettingsTabs categories={settingsCategories} activeCategoryId={activeCategoryId} onSelectCategory={selectCategory} />
        <div className="grid gap-4 xl:grid-cols-2">
          {activeCategory.fields.map((field) => (
            <SettingsField key={field.key} field={field} value={values[field.key] ?? ''} onChange={updateValue} />
          ))}
        </div>
        {message ? <p className="break-words text-sm font-medium text-slate-700">{message}</p> : null}
      </PageSection>
    </div>
  )
}

/**
 * 后端快照字段转为 key-value 映射，缺失字段由元数据默认空值兜底。
 */
function readFieldValues(config: Record<string, unknown> | null): Map<string, string> {
  const fields = Array.isArray(config?.fields) ? config.fields as SettingsFieldSnapshot[] : []
  return new Map(fields.map((field) => [String(field.key || ''), String(field.value ?? '')]))
}

/**
 * 初始化表单值时，写入专用字段不读取快照值，普通 boolean 字段转成 checkbox 状态。
 */
function readInitialFieldValue(field: SettingsFieldDefinition, fieldValues: Record<'biliConfig' | 'botConfig', Map<string, string>>): string | boolean {
  const rawValue = fieldValues[field.file].get(field.key) || defaultSettingsValue(field.key)
  if (field.writeOnly) {
    return ''
  }
  if (field.key === 'adminContactQQ') {
    return qqFromAdminContact(fieldValues.biliConfig.get('adminContact') || '', fieldValues.biliConfig.get('admin') || '')
  }
  if (field.key === 'adminsText') {
    return adminLinesFromSnapshot(fieldValues.botConfig.get('admins') || '')
  }
  if (field.key === 'imageConfig.badgeEnable.choice') {
    return badgeChoiceFromFields(fieldValues.biliConfig)
  }
  if (field.type === 'boolean') {
    return rawValue === 'true'
  }
  return rawValue
}

/**
 * 快照缺失字段沿用后端 DTO 默认值，避免文件级保存时把空值误转成 0 或 false。
 */
function defaultSettingsValue(key: string): string {
  const defaults: Record<string, string> = {
    'platform.type': 'onebot11',
    'platform.adapter': 'onebot11',
    'platform.onebot11.host': '127.0.0.1',
    'platform.onebot11.port': '3001',
    'platform.onebot11.useTls': 'false',
    'platform.onebot11.heartbeatInterval': '30000',
    'platform.onebot11.reconnectInterval': '5000',
    'platform.onebot11.sendMode': 'base64',
    'platform.onebot11.maxReconnectAttempts': '-1',
    'platform.onebot11.connectTimeout': '10000',
    'webui.enabled': 'false',
    'webui.host': '127.0.0.1',
    'webui.port': '18080',
    'webui.tokenTtlSeconds': '3600',
    'enableConfig.debugMode': 'false',
    'enableConfig.drawEnable': 'true',
    'enableConfig.pushDrawEnable': 'true',
    'enableConfig.notifyEnable': 'true',
    'enableConfig.liveCloseNotifyEnable': 'true',
    'enableConfig.lowSpeedEnable': 'true',
    'enableConfig.translateEnable': 'false',
    'enableConfig.proxyEnable': 'false',
    'enableConfig.cacheClearEnable': 'true',
    'accountConfig.autoFollow': 'true',
    'accountConfig.followGroup': 'Bot关注',
    'checkConfig.lowSpeedTime': '22-8',
    'checkConfig.lowSpeedRange': '60-240',
    'checkConfig.normalRange': '30-120',
    'checkConfig.checkReportInterval': '10',
    'checkConfig.timeout': '10',
    'imageConfig.quality': '1000w',
    'imageConfig.theme': 'v3',
    'imageConfig.defaultColor': '#d3edfa',
    'imageConfig.cardOrnament': 'FanCard',
    'imageConfig.timeDisplayMode': 'ABSOLUTE',
    'imageConfig.colorGenerator.hueStep': '30',
    'imageConfig.colorGenerator.lockSB': 'true',
    'imageConfig.colorGenerator.saturation': '0.25',
    'imageConfig.colorGenerator.brightness': '1',
    'imageConfig.badgeEnable.choice': 'left',
    'templateConfig.footer.footerAlign': 'LEFT',
    'cacheConfig.downloadOriginal': 'true',
    'cacheConfig.expires.DRAW': '7',
    'cacheConfig.expires.IMAGES': '7',
    'cacheConfig.expires.EMOJI': '7',
    'cacheConfig.expires.USER': '7',
    'cacheConfig.expires.OTHER': '7',
    'pushConfig.messageInterval': '100',
    'pushConfig.pushInterval': '500',
    'pushConfig.toShortLink': 'false',
    'templateConfig.defaultDynamicPush': 'OneMsg',
    'templateConfig.defaultLivePush': 'OneMsg',
    'templateConfig.defaultLiveClose': 'SimpleMsg',
    'linkResolveConfig.triggerMode': 'At',
    'linkResolveConfig.drawEnable': 'true',
    'linkResolveConfig.returnLink': 'false',
    'translateConfig.cutLine': '\n\n〓〓〓 翻译 〓〓〓\n',
  }
  return defaults[key] || ''
}

/**
 * 保存前按文件归属提取当前分区字段，避免跨文件 payload 混写。
 */
function pickValuesForFile(fields: SettingsFieldDefinition[], values: SettingsFormValues, file: 'biliConfig' | 'botConfig'): Record<string, unknown> {
  return Object.fromEntries(fields
    .filter((field) => field.file === file)
    .map((field) => [field.key, values[field.key] ?? '']))
}

/**
 * 特殊敏感字段由 save helper 的专用参数传入，其余字段继续走元数据映射。
 */
function omitKey(values: Record<string, unknown>, key: string): Record<string, unknown> {
  return Object.fromEntries(Object.entries(values).filter(([entryKey]) => entryKey !== key))
}

/**
 * adminContact 读取时尽量还原成 QQ 数字，兼容旧 admin 数字字段作为兜底。
 */
function qqFromAdminContact(contact: string, admin: string): string {
  const matched = contact.trim().match(/^onebot11:private:(\d+)$/)
  return matched ? matched[1] : admin
}

/**
 * bot.yml 管理员 DTO 在页面上按“群号:QQ号”逐行展示，方便直接编辑。
 */
function adminLinesFromSnapshot(value: string): string {
  try {
    const admins = JSON.parse(value || '[]')
    if (!Array.isArray(admins)) return ''
    return admins.flatMap((item) => {
      const groupId = Number(item?.groupId) || groupIdFromContact(item?.groupContact)
      const userIds = Array.isArray(item?.userIds) && item.userIds.length > 0
        ? item.userIds
        : (Array.isArray(item?.userContacts) ? item.userContacts.map(userIdFromContact).filter(Boolean) : [])
      return groupId ? userIds.map((userId: unknown) => `${groupId}:${userId}`) : []
    }).join('\n')
  } catch {
    return ''
  }
}

/**
 * 徽章选择框聚合 left/right 两个后端字段，显示层不暴露内部布尔拆分。
 */
function badgeChoiceFromFields(values: Map<string, string>): string {
  const left = values.get('imageConfig.badgeEnable.left') !== 'false'
  const right = values.get('imageConfig.badgeEnable.right') === 'true'
  if (left && right) return 'both'
  if (right) return 'right'
  if (left) return 'left'
  return 'none'
}

/**
 * 群联系人 subject 只提取 OneBot11 数字群号，无法识别时跳过该行。
 */
function groupIdFromContact(contact: unknown): number {
  const matched = String(contact || '').match(/^onebot11:group:(\d+)$/)
  return matched ? Number.parseInt(matched[1], 10) : 0
}

/**
 * 私聊联系人 subject 只提取 OneBot11 数字 QQ，无法识别时跳过该成员。
 */
function userIdFromContact(contact: unknown): number {
  const matched = String(contact || '').match(/^onebot11:private:(\d+)$/)
  return matched ? Number.parseInt(matched[1], 10) : 0
}
