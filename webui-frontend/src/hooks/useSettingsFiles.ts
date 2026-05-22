import { useCallback, useEffect, useMemo, useState } from 'react'
import { loadBiliConfig, loadBotConfig, saveBiliConfig, saveBotConfig, type WebUiBiliConfigSaveInput, type WebUiBotConfigSaveInput } from '../api/settings'
import type { WebUiJsonRequestOptions } from '../api/http'
import { useHighRiskConfirmation } from './useHighRiskConfirmation'

type UseSettingsFilesOptions = WebUiJsonRequestOptions

/**
 * 系统配置页把读取、保存和确认逻辑收敛到一个 hook，避免页面再散落 fetch。
 */
export function useSettingsFiles(options: UseSettingsFilesOptions = {}) {
  const {fetchImpl, storage, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    storage,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin, storage])
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

  const saveBili = useCallback(async (input: Omit<WebUiBiliConfigSaveInput, 'confirmationPassword'>) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认保存')
    if (!confirmationPassword) {
      return null
    }
    return saveBiliConfig({...input, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  const saveBot = useCallback(async (input: Omit<WebUiBotConfigSaveInput, 'confirmationPassword'>) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认保存')
    if (!confirmationPassword) {
      return null
    }
    return saveBotConfig({...input, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  return {biliConfig, botConfig, loading, reload, saveBili, saveBot}
}
