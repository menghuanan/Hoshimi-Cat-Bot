import { useCallback, useEffect, useMemo, useState } from 'react'
import { buildLogClearPayload, clearLogSource, listLogSources, readLogWindow } from '../api/logs'
import type { WebUiJsonRequestOptions } from '../api/http'
import type { WebUiParsedLogRow } from '../types/logs'
import { useCenteredConfirmation } from './useCenteredConfirmation'
import { useHighRiskConfirmation } from './useHighRiskConfirmation'

type UseLogsOptions = WebUiJsonRequestOptions & {
  tailLines?: number
  autoRefreshMs?: number
}

/**
 * 日志行解析兼容 `[LEVEL] [module] message` 和纯文本行，过滤时纯文本归为 PLAIN。
 */
function parseLogRow(raw: string): WebUiParsedLogRow {
  const match = raw.match(/^\[([A-Z]+)]\s+\[([^\]]+)]\s*(.*)$/)
  if (!match) {
    return {raw, level: 'PLAIN', module: 'default', message: raw}
  }
  return {
    raw,
    level: match[1],
    module: match[2],
    message: match[3],
  }
}

/**
 * 唯一值提取保持原始出现顺序，避免下拉框在刷新时跳动。
 */
function uniqueValues(values: string[]): string[] {
  return Array.from(new Set(values.filter(Boolean)))
}

/**
 * 日志页把来源、窗口和清空动作封成一个 hook，清空仍然走双重确认。
 */
export function useLogs(options: UseLogsOptions = {}) {
  const {tailLines = 500, autoRefreshMs = 30_000, fetchImpl, storage, redirectToLogin} = options
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
  const [levelFilter, setLevelFilter] = useState('all')
  const [moduleFilter, setModuleFilter] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [autoRefresh, setAutoRefresh] = useState(false)
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

  useEffect(() => {
    if (!autoRefresh || !sourceId) {
      return undefined
    }
    // 自动刷新只重读当前窗口，来源列表仍由手动刷新控制。
    const timer = window.setInterval(() => {
      void reloadWindow(sourceId)
    }, autoRefreshMs)
    return () => window.clearInterval(timer)
  }, [autoRefresh, autoRefreshMs, reloadWindow, sourceId])

  const parsedRows = useMemo(() => rows.map(parseLogRow), [rows])
  const levels = useMemo(() => uniqueValues(parsedRows.map((row) => row.level).filter((level) => level !== 'PLAIN')), [parsedRows])
  const modules = useMemo(() => uniqueValues(parsedRows.map((row) => row.module).filter((moduleName) => moduleName !== 'default')), [parsedRows])
  const filteredRows = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase()
    return parsedRows.filter((row) => {
      const matchesLevel = levelFilter === 'all' || row.level === levelFilter
      const matchesModule = moduleFilter === 'all' || row.module === moduleFilter
      const matchesKeyword = !normalizedKeyword || row.raw.toLowerCase().includes(normalizedKeyword)
      return matchesLevel && matchesModule && matchesKeyword
    })
  }, [keyword, levelFilter, moduleFilter, parsedRows])

  /**
   * 导出当前过滤结果为纯文本，同时返回文本便于测试和调用方提示。
   */
  const exportFilteredRows = useCallback(() => {
    const text = filteredRows.map((row) => row.raw).join('\n')
    if (typeof document !== 'undefined' && typeof window.URL.createObjectURL === 'function') {
      const blob = new Blob([text], {type: 'text/plain;charset=utf-8'})
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `${sourceId || 'webui-log'}.log`
      link.click()
      window.URL.revokeObjectURL(url)
    }
    return text
  }, [filteredRows, sourceId])

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
    parsedRows,
    filteredRows,
    levels,
    modules,
    levelFilter,
    moduleFilter,
    keyword,
    autoRefresh,
    loading,
    setSourceId,
    setLevelFilter,
    setModuleFilter,
    setKeyword,
    setAutoRefresh,
    reload,
    reloadSources,
    reloadWindow,
    exportFilteredRows,
    clearCurrentLog,
    buildLogClearPayload,
  }
}
