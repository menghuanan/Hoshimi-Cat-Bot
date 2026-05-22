import { useEffect, useMemo, useState } from 'react'
import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { SettingsField } from '../components/settings/SettingsField'
import { SettingsTabs } from '../components/settings/SettingsTabs'
import { useSettingsFiles } from '../hooks/useSettingsFiles'
import { settingsCategories, type SettingsCategoryId, type SettingsFieldDefinition } from '../settings/settingsSchema'

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
  const [values, setValues] = useState<SettingsFormValues>({})
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const activeCategory = settingsCategories.find((category) => category.id === activeCategoryId) || settingsCategories[0]
  const restartRequired = activeCategory.fields.some((field) => field.restartRequired)

  const fieldValues = useMemo(() => ({
    biliConfig: readFieldValues(biliConfig),
    botConfig: readFieldValues(botConfig),
  }), [biliConfig, botConfig])

  useEffect(() => {
    // 切换分区或刷新快照后重建当前表单，写入专用字段保持空值不回显。
    setValues(Object.fromEntries(activeCategory.fields.map((field) => [
      field.key,
      readInitialFieldValue(field, fieldValues[field.file].get(field.key)),
    ])))
  }, [activeCategory, fieldValues])

  /**
   * 字段变更只更新当前表单值，保存时再按文件边界拆分提交。
   */
  const updateValue = (key: string, value: string | boolean) => {
    setValues((current) => ({...current, [key]: value}))
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
      const biliToken = String(biliConfig?.snapshotToken || '')
      const botToken = String(botConfig?.snapshotToken || '')
      if (biliToken && Object.keys(biliValues).length > 0) {
        await saveBili({
          snapshotToken: biliToken,
          proxyText: String(biliValues['proxyConfig.proxy'] || ''),
          fields: omitKey(biliValues, 'proxyConfig.proxy'),
        })
      }
      if (botToken && Object.keys(botValues).length > 0) {
        await saveBot({
          snapshotToken: botToken,
          token: String(botValues['platform.onebot11.token'] || ''),
          fields: omitKey(botValues, 'platform.onebot11.token'),
        })
      }
      await reload()
      setMessage('配置已提交')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div data-page="settings" className="space-y-6">
      <PageSection title="系统配置" description="保存配置会携带 snapshotToken，并通过统一确认弹窗提交 WebUI 密码。">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <StatusCard label="BiliConfig 快照" value={loading ? '--' : shortToken(biliConfig?.snapshotToken)} tone="emerald" />
          <StatusCard label="bot.yml 快照" value={loading ? '--' : shortToken(botConfig?.snapshotToken)} tone="sky" />
          <StatusCard label="敏感输入" value="写入专用" tone="amber" detail="空白保存将保留现有敏感值" />
        </div>
      </PageSection>

      <PageSection
        title="写入设置"
        description="仅填写需要替换的敏感值；后端不会把现有代理、Cookie 或 Token 明文回显给前端。"
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
        <SettingsTabs categories={settingsCategories} activeCategoryId={activeCategoryId} onSelectCategory={setActiveCategoryId} />
        {restartRequired ? <p className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">当前分区包含需要重启后生效的配置。</p> : null}
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
function readInitialFieldValue(field: SettingsFieldDefinition, rawValue = ''): string | boolean {
  if (field.writeOnly) {
    return ''
  }
  if (field.type === 'boolean') {
    return rawValue === 'true'
  }
  return rawValue
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
 * token 只用于人工识别当前快照，不在界面展示完整值。
 */
function shortToken(value: unknown): string {
  const token = String(value || '--')
  return token.length > 12 ? `${token.slice(0, 12)}...` : token
}
