import { useCallback, useEffect, useMemo, useState } from 'react'
import { loadBiliConfig, loadBotConfig, saveBiliConfig, saveBotConfig, type WebUiBiliConfigSaveInput, type WebUiBotConfigSaveInput } from '../api/settings'
import type { WebUiJsonRequestOptions } from '../api/http'
import { useHighRiskConfirmation } from './useHighRiskConfirmation'

type UseSettingsFilesOptions = WebUiJsonRequestOptions

type ConfigSnapshotField = {
  key?: string
  value?: string
}

/**
 * 系统配置页把读取、保存和确认逻辑收敛到一个 hook，避免页面再散落 fetch。
 */
export function useSettingsFiles(options: UseSettingsFilesOptions = {}) {
  const {fetchImpl, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin])
  const {requestHighRiskConfirmation} = useHighRiskConfirmation()
  const [biliConfig, setBiliConfig] = useState<Record<string, unknown> | null>(null)
  const [botConfig, setBotConfig] = useState<Record<string, unknown> | null>(null)
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    setLoading(true)
    const [nextBiliConfig, nextBotConfig] = await Promise.all([
      loadBiliConfig(requestOptions),
      loadBotConfig(requestOptions),
    ])
    setBiliConfig(nextBiliConfig)
    setBotConfig(nextBotConfig)
    setLoading(false)
  }, [requestOptions])

  useEffect(() => {
    // 初始加载异步排队，满足 React hooks lint 对 effect 内状态更新的约束。
    const timer = window.setTimeout(() => {
      void reload()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [reload])

  /**
   * 保存前的高风险确认文案由调用方传入，默认仍然保持简短密码确认。
   */
  const saveBili = useCallback(async (input: Omit<WebUiBiliConfigSaveInput, 'confirmationPassword'>, confirmationMessage = '请输入 WebUI 密码确认保存') => {
    const confirmationPassword = await requestHighRiskConfirmation(confirmationMessage)
    if (!confirmationPassword) {
      return null
    }
    return saveBiliConfig({...input, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  /**
   * bot.yml 保存也复用同一套确认入口，必要时由页面层替换成更具体的风险文案。
   */
  const saveBot = useCallback(async (input: Omit<WebUiBotConfigSaveInput, 'confirmationPassword'>, confirmationMessage = '请输入 WebUI 密码确认保存') => {
    const confirmationPassword = await requestHighRiskConfirmation(confirmationMessage)
    if (!confirmationPassword) {
      return null
    }
    return saveBotConfig({...input, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  /**
   * 保存成功后把最新字段值合并回本地快照，避免页面重新读取旧快照把输入刷回旧值。
   */
  const patchBiliConfig = useCallback((values: Record<string, unknown>, snapshotToken?: string) => {
    setBiliConfig((current) => patchSnapshotFields(current, values, snapshotToken))
  }, [])

  /**
   * bot.yml 保存成功后也要乐观更新快照，保证切换分区后仍然看到刚保存的值。
   */
  const patchBotConfig = useCallback((values: Record<string, unknown>, snapshotToken?: string) => {
    setBotConfig((current) => patchSnapshotFields(current, values, snapshotToken))
  }, [])

  return {biliConfig, botConfig, loading, reload, saveBili, saveBot, patchBiliConfig, patchBotConfig}
}

/**
 * 快照对象同步字段值和后端返回的新令牌，避免后续保存继续携带旧 snapshotToken。
 */
function patchSnapshotFields(
  snapshot: Record<string, unknown> | null,
  values: Record<string, unknown>,
  snapshotToken?: string,
): Record<string, unknown> | null {
  if (!snapshot) {
    return snapshot
  }
  const fields = Array.isArray(snapshot.fields) ? snapshot.fields as ConfigSnapshotField[] : []
  const nextFields = fields.map((field) => ({
    ...field,
    value: Object.prototype.hasOwnProperty.call(values, String(field.key || ''))
      ? String(values[String(field.key || '')] ?? '')
      : field.value,
  }))
  return snapshotToken ? {...snapshot, snapshotToken, fields: nextFields} : {...snapshot, fields: nextFields}
}
