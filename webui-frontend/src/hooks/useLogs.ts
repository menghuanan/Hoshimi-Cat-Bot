import { useCallback, useEffect, useMemo, useState } from 'react'
import { flushSync } from 'react-dom'
import { listLogSources, readLogWindow } from '../api/logs'
import type { WebUiJsonRequestOptions } from '../api/http'
import type { WebUiParsedLogRow } from '../types/logs'

type UseLogsOptions = WebUiJsonRequestOptions & {
  tailLines?: number
  autoRefreshMs?: number
}

const logsAutoRefreshCookieName = 'dynamic_bot_webui_logs_auto_refresh'

/**
 * 日志行解析兼容 `[LEVEL] [module] message` 和纯文本行，过滤时纯文本归为 PLAIN。
 */
function parseLogRow(raw: string): WebUiParsedLogRow {
  const match = raw.match(/^\[([A-Z]+)]\s+\[([^\]]+)]\s*(.*)$/)
  if (match) {
    return {
      raw,
      level: match[1],
      module: match[2],
      message: match[3],
    }
  }
  // 文件日志来自 logback.xml 的固定模式，级别后面的 logger 名称就是模块筛选值。
  const logbackMatch = raw.match(/^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\[[^\]]+]\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+([^\s]+)\s+-\s*(.*)$/)
  if (logbackMatch) {
    return {
      raw,
      level: logbackMatch[1],
      module: logbackMatch[2],
      message: logbackMatch[3],
    }
  }
  const timestampedMatch = raw.match(/\b(TRACE|DEBUG|INFO|WARN|ERROR)\b\s+\[([^\]]+)]\s*(.*)$/)
  if (timestampedMatch) {
    return {
      raw,
      level: timestampedMatch[1],
      module: timestampedMatch[2],
      message: timestampedMatch[3],
    }
  }
  return {raw, level: 'PLAIN', module: 'default', message: raw}
}

/**
 * 唯一值提取保持原始出现顺序，避免下拉框在刷新时跳动。
 */
function uniqueValues(values: string[]): string[] {
  return Array.from(new Set(values.filter(Boolean)))
}

/**
 * 自动刷新偏好从 cookie 读取，保证重新打开日志页时沿用用户选择。
 */
function readLogsAutoRefreshCookie(): boolean {
  if (typeof document === 'undefined') return false
  return document.cookie.split(';').some((entry) => entry.trim() === `${logsAutoRefreshCookieName}=true`)
}

/**
 * 自动刷新偏好写入站点 cookie，不依赖后端会话或本地存储。
 */
function writeLogsAutoRefreshCookie(value: boolean) {
  if (typeof document === 'undefined') return
  document.cookie = `${logsAutoRefreshCookieName}=${value ? 'true' : 'false'}; Max-Age=31536000; path=/; SameSite=Lax`
}

/**
 * 日志页把来源、窗口、本地清屏和自动刷新偏好封成一个 hook。
 */
export function useLogs(options: UseLogsOptions = {}) {
  const {tailLines = 500, autoRefreshMs = 30_000, fetchImpl, storage, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    storage,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin, storage])
  const [sources, setSources] = useState<unknown[]>([])
  const [sourceId, setSourceId] = useState('')
  const [rows, setRows] = useState<string[]>([])
  const [levelFilter, setLevelFilter] = useState('all')
  const [moduleFilter, setModuleFilter] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [autoRefresh, setAutoRefreshState] = useState(readLogsAutoRefreshCookie)
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
    const nextSources = await reloadSources()
    const nextSourceId = sourceId || nextSources[0]?.id || ''
    await reloadWindow(nextSourceId)
    setLoading(false)
  }, [reloadSources, reloadWindow, sourceId])

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

  const setAutoRefresh = useCallback((nextValue: boolean) => {
    writeLogsAutoRefreshCookie(nextValue)
    setAutoRefreshState(nextValue)
  }, [])

  const clearCurrentLog = useCallback(async (currentSourceId = sourceId) => {
    // 清空当前日志只影响页面窗口，避免等待后端删除文件或清空日志源。
    void currentSourceId
    flushSync(() => setRows([]))
    return []
  }, [sourceId])

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
  }
}
