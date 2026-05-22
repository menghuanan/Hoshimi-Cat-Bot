import { useCallback, useEffect, useMemo, useState } from 'react'
import { buildLogClearPayload, clearLogSource, listLogSources, readLogWindow } from '../api/logs'
import type { WebUiJsonRequestOptions } from '../api/http'
import { useCenteredConfirmation } from './useCenteredConfirmation'
import { useHighRiskConfirmation } from './useHighRiskConfirmation'

type UseLogsOptions = WebUiJsonRequestOptions & {
  tailLines?: number
}

/**
 * 日志页把来源、窗口和清空动作封成一个 hook，清空仍然走双重确认。
 */
export function useLogs(options: UseLogsOptions = {}) {
  const {tailLines = 500, fetchImpl, storage, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    storage,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin, storage])
  const requestCenteredConfirmation = useCenteredConfirmation()
  const {requestHighRiskConfirmation} = useHighRiskConfirmation()
  const [sources, setSources] = useState<unknown[]>([])
  const [sourceId, setSourceId] = useState('')
  const [rows, setRows] = useState<string[]>([])
  const [loading, setLoading] = useState(true)

  const reloadSources = useCallback(async () => {
    const payload = await listLogSources(requestOptions) as {sources?: Array<{id: string}>}
    const nextSources = Array.isArray(payload?.sources) ? payload.sources : []
    setSources(nextSources)
    setSourceId((current) => current || nextSources[0]?.id || '')
    return nextSources
  }, [requestOptions])

  const reloadWindow = useCallback(async (nextSourceId = sourceId) => {
    if (!nextSourceId) {
      setRows([])
      return []
    }
    const payload = await readLogWindow(nextSourceId, tailLines, requestOptions) as {text?: string}
    const nextRows = String(payload?.text || '').split(/\r?\n/).filter(Boolean)
    setRows(nextRows)
    return nextRows
  }, [requestOptions, sourceId, tailLines])

  const reload = useCallback(async () => {
    setLoading(true)
    await reloadSources()
    await reloadWindow()
    setLoading(false)
  }, [reloadSources, reloadWindow])

  useEffect(() => {
    // 日志初始加载异步排队，避免 effect 同步触发多段 state 更新。
    const timer = window.setTimeout(() => {
      void reload()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [reload])

  const clearCurrentLog = useCallback(async (currentSourceId = sourceId) => {
    if (!await requestCenteredConfirmation('确认清空当前日志来源的内容？')) {
      return null
    }
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认清空日志')
    if (!confirmationPassword) {
      return null
    }
    return clearLogSource(currentSourceId, confirmationPassword, requestOptions)
  }, [requestCenteredConfirmation, requestHighRiskConfirmation, requestOptions, sourceId])

  return {
    sources,
    sourceId,
    rows,
    loading,
    setSourceId,
    reload,
    reloadSources,
    reloadWindow,
    clearCurrentLog,
    buildLogClearPayload,
  }
}
