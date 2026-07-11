import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  loadBiliConfig,
  loadBiliData,
  loadBotConfig,
  loadSettingsSaveJob,
  saveBiliConfig,
  saveBotConfig,
  saveSettingsBatch,
  type WebUiBiliConfigSaveInput,
  type WebUiBotConfigSaveInput,
  type WebUiSettingsBatchSaveInput,
} from '../api/settings'
import type { WebUiJsonRequestOptions } from '../api/http'
import type { WebUiConfigHotReloadJob } from '../types/settings'
import { readSessionPassword } from '../auth/sessionCredential'

type UseSettingsFilesOptions = WebUiJsonRequestOptions

type ConfigSnapshotField = {
  key?: string
  value?: string
}

/**
 * 系统配置页把读取和保存逻辑收敛到一个 hook，避免页面再散落 fetch。
 */
export function useSettingsFiles(options: UseSettingsFilesOptions = {}) {
  const {fetchImpl, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin])
  const [biliConfig, setBiliConfig] = useState<Record<string, unknown> | null>(null)
  const [biliData, setBiliData] = useState<Record<string, unknown> | null>(null)
  const [botConfig, setBotConfig] = useState<Record<string, unknown> | null>(null)
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    setLoading(true)
    const [nextBiliConfig, nextBiliData, nextBotConfig] = await Promise.all([
      loadBiliConfig(requestOptions),
      loadBiliData(requestOptions),
      loadBotConfig(requestOptions),
    ])
    setBiliConfig(nextBiliConfig)
    setBiliData(nextBiliData)
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
   * 单文件保存透明复用当前登录凭据，不再向用户索取二次确认密码。
   */
  const saveBili = useCallback(async (input: Omit<WebUiBiliConfigSaveInput, 'confirmationPassword'>) => {
    return saveBiliConfig({...input, confirmationPassword: readSessionPassword()}, requestOptions)
  }, [requestOptions])

  /**
   * bot.yml 保存复用当前登录凭据并保持原后端字段契约。
   */
  const saveBot = useCallback(async (input: Omit<WebUiBotConfigSaveInput, 'confirmationPassword'>) => {
    return saveBotConfig({...input, confirmationPassword: readSessionPassword()}, requestOptions)
  }, [requestOptions])

  /**
   * 批量保存会轮询热重载任务直到成功或失败，避免页面在仅入队时误报已生效。
   */
  const saveBatch = useCallback(async (input: WebUiSettingsBatchSaveInput) => {
    const accepted = await saveSettingsBatch(attachConfirmationPassword(input, readSessionPassword()), requestOptions)
    return waitForSettingsSaveJob(accepted, requestOptions)
  }, [requestOptions])

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

  /**
   * BiliData 乐观更新只处理设置页可编辑字段，不把订阅内部结构暴露给页面。
   */
  const patchBiliData = useCallback((values: Record<string, unknown>, snapshotToken?: string) => {
    setBiliData((current) => patchSnapshotFields(current, values, snapshotToken))
  }, [])

  return {
    biliConfig,
    biliData,
    botConfig,
    loading,
    reload,
    saveBili,
    saveBot,
    saveBatch,
    patchBiliConfig,
    patchBiliData,
    patchBotConfig,
  }
}

/**
 * 当前登录凭据复制到所有子 payload，保持后端 batch guard 和 API 契约不变。
 */
function attachConfirmationPassword(
  input: WebUiSettingsBatchSaveInput,
  confirmationPassword: string,
): WebUiSettingsBatchSaveInput {
  return {
    biliConfig: input.biliConfig ? {...input.biliConfig, confirmationPassword} : undefined,
    biliData: input.biliData ? {...input.biliData, confirmationPassword} : undefined,
    botConfig: input.botConfig ? {...input.botConfig, confirmationPassword} : undefined,
  }
}

/**
 * 轮询设置保存 job 使用 60 秒上限，避免后端异常时页面永久处于保存中。
 */
async function waitForSettingsSaveJob(
  accepted: WebUiConfigHotReloadJob,
  requestOptions: WebUiJsonRequestOptions,
): Promise<WebUiConfigHotReloadJob> {
  if (accepted.phase === 'APPLIED' || accepted.phase === 'FAILED') {
    return accepted
  }
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    await sleep(500)
    const current = await loadSettingsSaveJob(accepted.jobId, requestOptions)
    if (current.phase === 'APPLIED' || current.phase === 'FAILED') {
      return current
    }
  }
  throw new Error('保存任务超时，请稍后刷新状态')
}

/**
 * 显式 sleep 让轮询节奏可读，测试可通过 mock fetch 快速进入终态。
 */
function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds))
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
