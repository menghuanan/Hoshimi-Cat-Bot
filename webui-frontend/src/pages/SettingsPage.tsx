import { useCallback, useMemo, useState } from 'react'
import { BiliQrLoginModal } from '../components/BiliQrLoginModal'
import { PageSection } from '../components/PageSection'
import { SettingsField } from '../components/settings/SettingsField'
import { SettingsTabs } from '../components/settings/SettingsTabs'
import { useSettingsFiles } from '../hooks/useSettingsFiles'
import { useBiliQrLogin } from '../hooks/useBiliQrLogin'
import { useRuntimeSummary } from '../hooks/useRuntimeSummary'
import { useToast } from '../hooks/useToast'
import { formatSaveResultMessage } from '../settings/settingsSaveResult'
import { buildBiliConfigSavePayload, buildBiliDataSavePayload, buildBotConfigSavePayload } from '../api/settings'
import { settingsCategories, validateSettingsValues, type SettingsCategoryDefinition, type SettingsCategoryId, type SettingsFieldDefinition } from '../settings/settingsSchema'
import type { WebUiConfigFileKind, WebUiConfigHotReloadJob } from '../types/settings'
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

type AdminDraftPair = {
  groupId: string
  userId: string
}

/**
 * 设置页按元数据渲染八个分区，敏感字段只允许空输入触发保留语义。
 */
export function SettingsPage() {
  const {loading, biliConfig, biliData, botConfig, saveBatch, patchBiliConfig, patchBiliData, patchBotConfig} = useSettingsFiles()
  const {showToast} = useToast()
  const {dashboard, loading: runtimeLoading, refresh: refreshRuntime} = useRuntimeSummary({pollIntervalMs: 60_000})
  const [activeCategoryId, setActiveCategoryId] = useState<SettingsCategoryId>('integration')
  // 子配置切换只重挂载显示层，用来重播入场动效，不改变表单值来源或保存语义。
  const [categoryMotionToken, setCategoryMotionToken] = useState(0)
  const [editedValues, setEditedValues] = useState<SettingsFormValues>({})
  const [saving, setSaving] = useState(false)
  const activeCategory = settingsCategories.find((category) => category.id === activeCategoryId) || settingsCategories[0]
  const allSettingsFields = useMemo(() => settingsCategories.flatMap((category) => category.fields), [])

  /** 扫码成功后立即刷新账号摘要，并通过全局 Toast 保留关闭弹窗后的结果反馈。 */
  const handleBiliLoginSucceeded = useCallback(async () => {
    showToast('success', 'BiliBili 登录成功')
    // 登录提交结果独立于摘要刷新；刷新失败只保留旧页面状态，不得撤销成功反馈。
    await refreshRuntime()
  }, [refreshRuntime, showToast])
  /** 登录超时通过全局 Toast 保留结果，关闭二维码弹窗后用户仍能看到下一步提示。 */
  const handleBiliLoginExpired = useCallback((message: string) => {
    showToast('warning', message)
  }, [showToast])
  const biliLogin = useBiliQrLogin({
    onSucceeded: handleBiliLoginSucceeded,
    onExpired: handleBiliLoginExpired,
  })

  const fieldValues = useMemo(() => ({
    biliConfig: readFieldValues(biliConfig),
    biliData: readFieldValues(biliData),
    botConfig: readFieldValues(botConfig),
  }), [biliConfig, biliData, botConfig])

  const initialValues = useMemo<SettingsFormValues>(() => {
    // 初始值来自快照，写入专用字段保持空值不回显。
    return Object.fromEntries(activeCategory.fields.map((field) => [
      field.key,
      readInitialFieldValue(field, fieldValues),
    ]))
  }, [activeCategory, fieldValues])

  const completeValues = useMemo<SettingsFormValues>(() => {
    // 完整值用于组装文件级 DTO，避免只保存当前表单时把同文件其他字段写成后端默认值。
    return {
      ...Object.fromEntries(allSettingsFields.map((field) => [
        field.key,
        readInitialFieldValue(field, fieldValues),
      ])),
      ...editedValues,
    }
  }, [allSettingsFields, editedValues, fieldValues])

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
   * 切换设置分区时丢弃未提交编辑，并让新分区的纯展示动效重新播放。
   */
  const selectCategory = (categoryId: SettingsCategoryId) => {
    setEditedValues({})
    setCategoryMotionToken((current) => current + 1)
    setActiveCategoryId(categoryId)
  }

  /**
   * 保存当前分区时分别写入 BiliConfig 和 bot.yml，并在 WebUI 对外绑定时额外强调暴露风险。
   */
  const saveActiveCategory = async () => {
    setSaving(true)
    try {
      const biliValues = pickValuesForFile(visibleSettingsFields, values, 'biliConfig')
      const biliDataValues = pickValuesForFile(visibleSettingsFields, values, 'biliData')
      const botValues = pickValuesForFile(visibleSettingsFields, values, 'botConfig')
      const validationErrors = validateSettingsValues({...biliValues, ...biliDataValues, ...botValues})
      if (validationErrors.length > 0) {
        showToast('error', validationErrors.join('；'))
        return
      }

      const completeBiliValues = pickValuesForFile(allSettingsFields, completeValues, 'biliConfig')
      const completeBiliDataValues = pickValuesForFile(allSettingsFields, completeValues, 'biliData')
      const completeBotValues = pickValuesForFile(allSettingsFields, completeValues, 'botConfig')
      const biliToken = String(biliConfig?.snapshotToken || '')
      const biliDataToken = String(biliData?.snapshotToken || '')
      const botToken = String(botConfig?.snapshotToken || '')
      const shouldSaveBili = hasChangedValuesForFile(visibleSettingsFields, editedValues, initialValues, values, 'biliConfig')
      const shouldSaveBiliData = hasChangedValuesForFile(visibleSettingsFields, editedValues, initialValues, values, 'biliData')
      const shouldSaveBot = hasChangedValuesForFile(visibleSettingsFields, editedValues, initialValues, values, 'botConfig')
      // 没有有效差异时不向后端提交 batch，避免空保存触发热重载或写盘。
      if (!shouldSaveBili && !shouldSaveBiliData && !shouldSaveBot) {
        showToast('warning', '没有检测到配置变更')
        return
      }
      const batchPayload: Record<string, Record<string, unknown>> = {}
      if (biliToken && shouldSaveBili) {
        batchPayload.biliConfig = buildBiliConfigSavePayload({
          snapshotToken: biliToken,
          confirmationPassword: '',
          proxyText: String(completeBiliValues['proxyConfig.proxy'] || ''),
          fields: omitKey(completeBiliValues, 'proxyConfig.proxy'),
        })
      }
      if (biliDataToken && shouldSaveBiliData) {
        batchPayload.biliData = buildBiliDataSavePayload({
          snapshotToken: biliDataToken,
          confirmationPassword: '',
          fields: completeBiliDataValues,
        })
      }
      if (botToken && shouldSaveBot) {
        const botFields = shouldSubmitAdminProjection(visibleSettingsFields, editedValues)
          ? omitKey(completeBotValues, 'platform.onebot11.token')
          : omitKeys(completeBotValues, ['platform.onebot11.token', 'adminsText'])
        batchPayload.botConfig = buildBotConfigSavePayload({
          snapshotToken: botToken,
          confirmationPassword: '',
          token: String(completeBotValues['platform.onebot11.token'] || ''),
          fields: botFields,
        })
      }

      const job = await saveBatch(batchPayload)
      if (!job) {
        showToast('warning', formatSaveResultMessage([]))
        return
      }
      if (job.phase === 'FAILED') {
        showToast('error', formatHotReloadJobMessage(job))
        return
      }

      patchBiliConfig(completeBiliValues, outcomeToken(job, 'BILI_CONFIG'))
      patchBiliData(completeBiliDataValues, outcomeToken(job, 'BILI_DATA'))
      patchBotConfig(completeBotValues, outcomeToken(job, 'BOT_CONFIG'))
      setEditedValues({})
      showToast('success', formatHotReloadJobMessage(job))
    } catch (error) {
      showToast('error', formatPasswordErrorMessage(error, '保存失败'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div data-page="settings" className="space-y-6">
      <PageSection
        title="B站账号"
        description="管理动态、直播和订阅请求使用的 B站登录状态"
        actions={(
          <button
            type="button"
            className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
            disabled={biliLogin.loading || biliLogin.session?.phase === 'COMMITTING'}
            onClick={() => void biliLogin.openLogin()}
          >
            {dashboard.accountLoggedIn ? '重新登录' : '扫码登录'}
          </button>
        )}
      >
        <div className="grid gap-4 border-y border-slate-200 bg-white px-4 py-4 sm:grid-cols-2">
          <div className="min-w-0">
            <p className="text-xs font-medium text-slate-500">登录状态</p>
            <p className={`mt-1 text-sm font-semibold ${dashboard.accountLoggedIn ? 'text-emerald-700' : 'text-rose-700'}`}>
              {runtimeLoading ? '同步中' : dashboard.accountLoggedIn ? '已登录' : '未登录'}
            </p>
          </div>
          <div className="min-w-0">
            <p className="text-xs font-medium text-slate-500">账号 UID</p>
            <p className="mt-1 break-words text-sm font-semibold text-slate-950">{dashboard.accountUid || '--'}</p>
          </div>
        </div>
      </PageSection>

      <PageSection
        title="系统配置"
        description="保存后自动热重载生效"
        actions={(
          <>
            {/* 顶部只保留保存操作和保存结果，标题由 PageSection 承载。 */}
            <button
              type="button"
              className="inline-flex items-center gap-2 rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
              disabled={saving || loading}
              onClick={saveActiveCategory}
            >
              {saving ? <span className="button-spinner" aria-hidden="true" /> : null}
              {saving ? '保存中…' : '保存'}
            </button>
          </>
        )}
      >
        <SettingsTabs categories={settingsCategories} activeCategoryId={activeCategoryId} onSelectCategory={selectCategory} />
        <div key={`${activeCategoryId}-${categoryMotionToken}`} className="space-y-4">
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
      <BiliQrLoginModal
        open={biliLogin.open}
        loading={biliLogin.loading}
        session={biliLogin.session}
        error={biliLogin.error}
        onClose={biliLogin.closeLogin}
        onRetry={biliLogin.retryLogin}
      />
    </div>
  )
}

/**
 * 当前分区只展开可见字段，确保页面态和保存态使用同一组 field key。
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
 * 按 key 顺序取出字段，避免条件渲染改变当前分区的阅读顺序。
 */
function fieldsByKeys(fields: SettingsFieldDefinition[], keys: string[]): SettingsFieldDefinition[] {
  const fieldByKey = new Map(fields.map((field) => [field.key, field]))
  return keys.flatMap((key) => {
    const field = fieldByKey.get(key)
    return field ? [field] : []
  })
}

/**
 * 设置分组使用统一边框容器，标题贴在边框上并与字段左边缘对齐。
 */
function SettingsGroup({title, fields, values, onChange}: {
  title: string
  fields: SettingsFieldDefinition[]
  values: SettingsFormValues
  onChange: (key: string, value: string | boolean) => void
}) {
  return (
    <section data-layout="single-column" className="settings-group-motion space-y-3">
      <fieldset className="mx-auto w-full md:w-3/4 rounded-lg border border-slate-200 bg-white px-4 pb-4 pt-3 shadow-sm">
        <legend className="px-2 text-sm font-semibold text-slate-900">{title}</legend>
        <div className="grid gap-4">
          {fields.map((field) => (
            <div key={field.key} className="settings-field-motion">
              {field.key === 'adminsText'
                ? <GroupAdminField value={String(values[field.key] || '')} onChange={(value) => onChange(field.key, value)} />
                : <SettingsField field={field} value={values[field.key] ?? ''} onChange={onChange} />}
            </div>
          ))}
        </div>
      </fieldset>
    </section>
  )
}

/**
 * 群普通管理员以卡片作为唯一编辑面，卡片变更立即进入页面待保存态。
 */
function GroupAdminField({value, onChange}: {value: string, onChange: (value: string) => void}) {
  const [draftState, setDraftState] = useState(() => ({
    sourceValue: value,
    pairs: initialAdminDraftPairs(value),
  }))
  const draftPairs = draftState.sourceValue === value ? draftState.pairs : initialAdminDraftPairs(value)

  /**
   * 卡片增删改同步更新草稿和待保存字段，用户直接点顶部保存也能提交当前卡片状态。
   */
  const applyDraftPairs = (nextPairs: AdminDraftPair[]) => {
    const nextValue = serializeAdminDraftPairs(nextPairs)
    setDraftState({sourceValue: nextValue, pairs: nextPairs})
    onChange(nextValue)
  }

  /**
   * 输入区直接编辑卡片数组，半填行保留到保存校验中提示用户补全。
   */
  const updatePair = (index: number, part: keyof AdminDraftPair, nextValue: string) => {
    applyDraftPairs(draftPairs.map((pair, pairIndex) => (
      pairIndex === index ? {...pair, [part]: nextValue} : pair
    )))
  }

  /**
   * 添加空白行后继续编辑，避免群普通管理员只能暂存一组映射。
   */
  const addPair = () => {
    setDraftState({sourceValue: value, pairs: [...draftPairs, createEmptyAdminPair()]})
  }

  /**
   * 删除卡片立即反映到待保存字段，删空后保留一张空卡作为后续新增入口。
   */
  const removePair = (index: number) => {
    applyDraftPairs(ensureAdminDraftPairs(draftPairs.filter((_, pairIndex) => pairIndex !== index)))
  }

  /**
   * 暂存按钮保留显式确认入口，同时压缩空白卡片避免页面继续展示无效行。
   */
  const stageDraft = () => {
    const compactedPairs = ensureAdminDraftPairs(draftPairs.filter((pair) => pair.groupId.trim() || pair.userId.trim()))
    applyDraftPairs(compactedPairs)
  }

  return (
    <div className="min-w-0 w-full rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm font-medium text-slate-800">群普通管理员</p>
        <button type="button" onClick={addPair} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50">添加一行</button>
      </div>
      {/* 管理员卡片即当前待保存列表，不再额外渲染右下角的只读信息块。 */}
      <div className="mt-3 space-y-3">
        {draftPairs.map((pair, index) => (
          <div key={`admin-draft-${index}`} className="grid gap-3 rounded-lg border border-slate-200 bg-slate-50 p-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="grid gap-1 text-xs font-medium text-slate-600">
                群聊
                <input
                  aria-label="群聊"
                  inputMode="numeric"
                  value={pair.groupId}
                  onChange={(event) => updatePair(index, 'groupId', event.target.value)}
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950"
                />
              </label>
              <label className="grid gap-1 text-xs font-medium text-slate-600">
                个人QQ号
                <input
                  aria-label="个人QQ号"
                  inputMode="numeric"
                  value={pair.userId}
                  onChange={(event) => updatePair(index, 'userId', event.target.value)}
                  className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950"
                />
              </label>
            </div>
            <div className="flex justify-end">
              {draftPairs.length > 1 || pair.groupId.trim() || pair.userId.trim() ? (
                <button type="button" onClick={() => removePair(index)} className="rounded-lg border border-rose-200 px-3 py-1.5 text-sm font-medium text-rose-700 hover:bg-rose-50">删除</button>
              ) : null}
            </div>
          </div>
        ))}
      </div>
      <div className="mt-4 flex justify-end">
        <button type="button" onClick={stageDraft} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">暂存</button>
      </div>
    </div>
  )
}

/**
 * 后端 job message 是热重载最终状态来源；没有 message 时按 phase 给出稳定兜底文案。
 */
function formatHotReloadJobMessage(job: WebUiConfigHotReloadJob): string {
  const baseMessage = job.message || (job.phase === 'APPLIED' ? '保存成功，配置已热重载' : '保存失败，请检查后重试')
  return job.webUiRedirectUrl ? `${baseMessage}，新地址：${job.webUiRedirectUrl}` : baseMessage
}

/**
 * 每个文件的新 snapshotToken 来自对应 outcome；缺失时让本地 patch 只更新字段值。
 */
function outcomeToken(job: WebUiConfigHotReloadJob, file: WebUiConfigFileKind): string | undefined {
  return job.outcomes?.find((outcome) => outcome.file === file)?.result?.snapshotToken
}

/**
 * 生成空白草稿行，方便用户直接继续添加下一组映射。
 */
function createEmptyAdminPair(): AdminDraftPair {
  return {groupId: '', userId: ''}
}

/**
 * 草稿初始化时至少保留一行输入，避免空状态下无法直接填写。
 */
function initialAdminDraftPairs(value: string): AdminDraftPair[] {
  const pairs = adminPairsFromText(value)
  return pairs.length > 0 ? pairs : [createEmptyAdminPair()]
}

/**
 * 删除草稿后如果没有任何行，就补回一行空白输入。
 */
function ensureAdminDraftPairs(pairs: AdminDraftPair[]): AdminDraftPair[] {
  return pairs.length > 0 ? pairs : [createEmptyAdminPair()]
}

/**
 * 暂存时压缩空白草稿，并保持“群号:QQ号”的页面态格式。
 */
function serializeAdminDraftPairs(pairs: AdminDraftPair[]): string {
  return pairs
    .filter((pair) => pair.groupId.trim() || pair.userId.trim())
    .map((pair) => `${pair.groupId}:${pair.userId}`)
    .join('\n')
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
 * 后端快照字段转为 key-value 映射，缺失字段由元数据默认值兜底。
 */
function readFieldValues(config: Record<string, unknown> | null): Map<string, string> {
  const fields = Array.isArray(config?.fields) ? config.fields as SettingsFieldSnapshot[] : []
  return new Map(fields.map((field) => [String(field.key || ''), String(field.value ?? '')]))
}

/**
 * 初始化表单值时，写入专用字段不读取快照值，普通 boolean 字段转成 checkbox 状态。
 */
function readInitialFieldValue(field: SettingsFieldDefinition, fieldValues: Record<'biliConfig' | 'biliData' | 'botConfig', Map<string, string>>): string | boolean {
  const rawValue = fieldValues[field.file].get(field.key) || defaultSettingsValue(field.key)
  if (field.writeOnly) {
    return ''
  }
  if (field.file === 'biliData') {
    return rawValue
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
 * 快照缺失字段沿用后端 DTO 默认值，避免保存时把空值误转成 0 或 false。
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
    'linkParseBlacklistContacts': '',
    'translateConfig.cutLine': '\n\n〓〓〓 翻译 〓〓〓\n',
  }
  return defaults[key] || ''
}

/**
 * 保存前按文件归属提取当前分区字段，避免跨文件 payload 混写。
 */
function pickValuesForFile(fields: SettingsFieldDefinition[], values: SettingsFormValues, file: 'biliConfig' | 'biliData' | 'botConfig'): Record<string, unknown> {
  return Object.fromEntries(fields
    .filter((field) => field.file === file)
    .map((field) => [field.key, values[field.key] ?? '']))
}

/**
 * 保存入口只提交当前值相对快照确实变化的文件，避免改回原值后仍触发热重载。
 */
function hasChangedValuesForFile(
  fields: SettingsFieldDefinition[],
  editedValues: SettingsFormValues,
  initialValues: SettingsFormValues,
  currentValues: SettingsFormValues,
  file: 'biliConfig' | 'biliData' | 'botConfig',
): boolean {
  return fields.some((field) => (
    field.file === file &&
    Object.prototype.hasOwnProperty.call(editedValues, field.key) &&
    normalizeSettingsDiffValue(currentValues[field.key]) !== normalizeSettingsDiffValue(initialValues[field.key])
  ))
}

/**
 * 表单差异比较统一把 checkbox 和输入框值转成稳定文本，避免类型表现差异造成误保存。
 */
function normalizeSettingsDiffValue(value: string | boolean | undefined): string {
  if (value === true) return 'true'
  if (value === false) return 'false'
  return String(value ?? '')
}

/**
 * 特殊敏感字段用 save helper 的专用参数传入，其余字段继续走元数据映射。
 */
function omitKey(values: Record<string, unknown>, key: string): Record<string, unknown> {
  return Object.fromEntries(Object.entries(values).filter(([entryKey]) => entryKey !== key))
}

/**
 * 多个特殊字段可按保存语义排除，避免完整快照补齐时把无关字段投影进 payload。
 */
function omitKeys(values: Record<string, unknown>, keys: string[]): Record<string, unknown> {
  const excluded = new Set(keys)
  return Object.fromEntries(Object.entries(values).filter(([entryKey]) => !excluded.has(entryKey)))
}

/**
 * adminsText 只有用户在管理员分区实际暂存或编辑后才提交，普通 bot 保存不重建管理员 DTO。
 */
function shouldSubmitAdminProjection(fields: SettingsFieldDefinition[], values: SettingsFormValues): boolean {
  return fields.some((field) => (
    field.key === 'adminsText' && Object.prototype.hasOwnProperty.call(values, field.key)
  ))
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
 * 徽章选项按 left/right 两个后端字段显示，避免把内部布尔拆分暴露给用户。
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
 * 群聊联系人的 subject 只提取 OneBot11 数字群号。
 */
function groupIdFromContact(contact: unknown): number {
  const matched = String(contact || '').match(/^onebot11:group:(\d+)$/)
  return matched ? Number.parseInt(matched[1], 10) : 0
}

/**
 * 私聊联系人的 subject 只提取 OneBot11 数字 QQ。
 */
function userIdFromContact(contact: unknown): number {
  const matched = String(contact || '').match(/^onebot11:private:(\d+)$/)
  return matched ? Number.parseInt(matched[1], 10) : 0
}
