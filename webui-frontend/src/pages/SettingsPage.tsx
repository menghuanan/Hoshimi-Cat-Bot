import { useMemo, useState } from 'react'
import { PageSection } from '../components/PageSection'
import { SettingsField } from '../components/settings/SettingsField'
import { SettingsTabs } from '../components/settings/SettingsTabs'
import { useSettingsFiles } from '../hooks/useSettingsFiles'
import { formatSaveResultMessage } from '../settings/settingsSaveResult'
import { settingsCategories, validateSettingsValues, type SettingsCategoryDefinition, type SettingsCategoryId, type SettingsFieldDefinition } from '../settings/settingsSchema'
import type { WebUiSettingsSaveResult } from '../types/settings'
import { formatPasswordErrorMessage } from '../utils/errorMessages'

type SettingsFieldSnapshot = {
  key?: string
  value?: string
}

type SettingsFormValues = Record<string, string | boolean>
type SettingsFieldGroup = {
  title: string
  fields: SettingsFieldDefinition[]
}

/**
 * 配置页按元数据渲染八个分区，敏感字段只允许空输入触发保留语义。
 */
export function SettingsPage() {
  const {loading, biliConfig, botConfig, saveBili, saveBot, patchBiliConfig, patchBotConfig} = useSettingsFiles()
  const [activeCategoryId, setActiveCategoryId] = useState<SettingsCategoryId>('integration')
  const [editedValues, setEditedValues] = useState<SettingsFormValues>({})
  const [saving, setSaving] = useState(false)
  const [saveStatus, setSaveStatus] = useState<{tone: 'neutral' | 'success' | 'error', message: string}>({tone: 'neutral', message: ''})
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
  const visibleFieldGroups = useMemo(() => visibleGroupsForCategory(activeCategory, values), [activeCategory, values])
  const visibleSettingsFields = useMemo(() => visibleFieldGroups.flatMap((group) => group.fields), [visibleFieldGroups])

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
    setSaveStatus({tone: 'neutral', message: ''})
    try {
      const biliValues = pickValuesForFile(visibleSettingsFields, values, 'biliConfig')
      const botValues = pickValuesForFile(visibleSettingsFields, values, 'botConfig')
      const validationErrors = validateSettingsValues({...biliValues, ...botValues})
      if (validationErrors.length > 0) {
        setSaveStatus({tone: 'error', message: validationErrors.join('；')})
        return
      }
      const completeBiliValues = pickValuesForFile(allSettingsFields, completeValues, 'biliConfig')
      const completeBotValues = pickValuesForFile(allSettingsFields, completeValues, 'botConfig')
      const biliToken = String(biliConfig?.snapshotToken || '')
      const botToken = String(botConfig?.snapshotToken || '')
      const saveResults: Array<WebUiSettingsSaveResult | null> = []
      if (biliToken && Object.keys(biliValues).length > 0) {
        saveResults.push(await saveBili({
          snapshotToken: biliToken,
          proxyText: String(completeBiliValues['proxyConfig.proxy'] || ''),
          fields: omitKey(completeBiliValues, 'proxyConfig.proxy'),
        }))
      }
      if (botToken && Object.keys(botValues).length > 0) {
        saveResults.push(await saveBot({
          snapshotToken: botToken,
          token: String(completeBotValues['platform.onebot11.token'] || ''),
          fields: omitKey(completeBotValues, 'platform.onebot11.token'),
        }))
      }
      const resultMessage = formatSaveResultMessage(saveResults)
      if (saveResults.some((result) => result === null)) {
        setSaveStatus({tone: 'neutral', message: resultMessage})
        return
      }
      if (saveResults.some((result) => result?.success === false)) {
        setSaveStatus({tone: 'error', message: resultMessage})
        return
      }
      patchBiliConfig(completeBiliValues)
      patchBotConfig(completeBotValues)
      setEditedValues({})
      setSaveStatus({tone: 'success', message: resultMessage})
    } catch (error) {
      setSaveStatus({tone: 'error', message: formatPasswordErrorMessage(error, '保存失败')})
    } finally {
      setSaving(false)
    }
  }

  return (
    <div data-page="settings" className="space-y-6">
      <PageSection
        title="写入设置"
        actions={(
          <>
          <button
            type="button"
            className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
            disabled={saving || loading}
            onClick={saveActiveCategory}
          >
            {saving ? '保存中' : '保存'}
          </button>
          {saveStatus.message ? (
            <span className={`text-sm font-medium ${saveStatus.tone === 'success' ? 'text-emerald-600' : saveStatus.tone === 'error' ? 'text-rose-600' : 'text-slate-600'}`}>
              {saveStatus.message}
            </span>
          ) : null}
          </>
        )}
      >
        <SettingsTabs categories={settingsCategories} activeCategoryId={activeCategoryId} onSelectCategory={selectCategory} />
        <div className="space-y-4">
          {visibleFieldGroups.map((group) => (
            <SettingsGroup
              key={group.title}
              title={group.title}
              fields={group.fields}
              values={values}
              onChange={updateValue}
            />
          ))}
        </div>
      </PageSection>
    </div>
  )
}

/**
 * 配置分组根据页面实际显示字段生成，保存范围也沿用同一组字段。
 */
function visibleGroupsForCategory(category: SettingsCategoryDefinition, values: SettingsFormValues): SettingsFieldGroup[] {
  if (category.id === 'integration') {
    const platformType = String(values['platform.type'] || 'onebot11')
    const platformKeys = platformType === 'qq_official'
      ? ['platform.type', 'platform.qqOfficial.appId', 'platform.qqOfficial.appSecret', 'platform.qqOfficial.botToken']
      : [
          'platform.type',
          'platform.adapter',
          'platform.onebot11.host',
          'platform.onebot11.port',
          'platform.onebot11.token',
          'platform.onebot11.useTls',
          'platform.onebot11.heartbeatInterval',
          'platform.onebot11.reconnectInterval',
          'platform.onebot11.sendMode',
          'platform.onebot11.maxReconnectAttempts',
          'platform.onebot11.connectTimeout',
        ]
    const webUiKeys = values['webui.enabled'] === true
      ? ['webui.enabled', 'webui.host', 'webui.port', 'webui.tokenTtlSeconds']
      : ['webui.enabled']
    return [
      {title: '平台配置', fields: fieldsByKeys(category.fields, platformKeys)},
      {title: 'WebUI 配置', fields: fieldsByKeys(category.fields, webUiKeys)},
    ]
  }
  return [{title: category.label, fields: category.fields}]
}

/**
 * 字段顺序以显式 key 列表为准，避免条件显示后改变旧 WebUI 的阅读顺序。
 */
function fieldsByKeys(fields: SettingsFieldDefinition[], keys: string[]): SettingsFieldDefinition[] {
  const fieldByKey = new Map(fields.map((field) => [field.key, field]))
  return keys.flatMap((key) => {
    const field = fieldByKey.get(key)
    return field ? [field] : []
  })
}

/**
 * 设置组统一渲染两列字段，管理员组对群管理映射使用专用双输入控件。
 */
function SettingsGroup({title, fields, values, onChange}: {
  title: string
  fields: SettingsFieldDefinition[]
  values: SettingsFormValues
  onChange: (key: string, value: string | boolean) => void
}) {
  return (
    <section data-layout="single-column" className="space-y-3">
      <h4 className="text-sm font-semibold text-slate-900">{title}</h4>
      {/* 字段按单列堆叠，避免大屏下配置项被拆成左右两栏。 */}
      <div className="grid gap-4">
        {fields.map((field) => field.key === 'adminsText' ? (
          // 群管理员草稿跟随父级保存值重挂载，避免 effect 中同步派生 state。
          <GroupAdminField key={`${field.key}:${String(values[field.key] || '')}`} value={String(values[field.key] || '')} onChange={(value) => onChange(field.key, value)} />
        ) : (
          <SettingsField key={field.key} field={field} value={values[field.key] ?? ''} onChange={onChange} />
        ))}
      </div>
    </section>
  )
}

/**
 * 群普通管理员按“群聊 + 个人QQ号”双输入编辑首条映射，并在下方列出现有映射。
 */
function GroupAdminField({value, onChange}: {value: string, onChange: (value: string) => void}) {
  const [draftText, setDraftText] = useState(value)
  const pairs = adminPairsFromText(value)
  const draftPairs = adminPairsFromText(draftText)
  const firstPair = draftPairs[0] || {groupId: '', userId: ''}

  /**
   * 输入区只更新卡片草稿，必须点击暂存后才写回页面待保存值。
   */
  const updatePair = (part: 'groupId' | 'userId', nextValue: string) => {
    const nextPairs = draftPairs.length > 0 ? [...draftPairs] : [{groupId: '', userId: ''}]
    nextPairs[0] = {...nextPairs[0], [part]: nextValue}
    setDraftText(nextPairs.map((pair) => `${pair.groupId}:${pair.userId}`).join('\n'))
  }

  /**
   * 暂存只提交到当前页面态，真正写入文件仍由页面右上角保存按钮处理。
   */
  const stageDraft = () => {
    onChange(draftText)
  }

  return (
    <div className="min-w-0 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <p className="text-sm font-medium text-slate-800">群普通管理员</p>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <label className="grid gap-1 text-xs font-medium text-slate-600">
          群聊
          <input
            aria-label="群聊"
            inputMode="numeric"
            value={firstPair.groupId}
            onChange={(event) => updatePair('groupId', event.target.value)}
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950"
          />
        </label>
        <label className="grid gap-1 text-xs font-medium text-slate-600">
          个人QQ号
          <input
            aria-label="个人QQ号"
            inputMode="numeric"
            value={firstPair.userId}
            onChange={(event) => updatePair('userId', event.target.value)}
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950"
          />
        </label>
      </div>
      <div className="mt-4 flex justify-end">
        <button type="button" onClick={stageDraft} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">暂存</button>
      </div>
      <div className="mt-3 space-y-1">
        {pairs.length > 0 ? pairs.map((pair, index) => (
          <p key={`${pair.groupId}-${pair.userId}-${index}`} className="text-sm text-slate-700">
            群聊：{pair.groupId || '--'} 管理员：{pair.userId || '--'}
          </p>
        )) : <p className="text-sm text-slate-500">暂无群普通管理员</p>}
      </div>
    </div>
  )
}

/**
 * 管理员文本解析只服务显示层，非法或半填行仍保留到保存校验中处理。
 */
function adminPairsFromText(value: string): Array<{groupId: string, userId: string}> {
  return value.split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [groupId = '', userId = ''] = line.split(/\s*[:：]\s*/, 2)
      return {groupId, userId}
    })
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
