import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchRuntimeSummary } from '../api/runtime'
import type { WebUiJsonRequestOptions } from '../api/http'
import type { WebUiDashboardRecentPushRecord, WebUiDashboardRuntimeFields, WebUiDashboardStatusTone, WebUiRuntimeSummary, WebUiRecentPushRecord } from '../types/runtime'

type UseRuntimeSummaryOptions = WebUiJsonRequestOptions & {
  pollIntervalMs?: number
}

/**
 * 将后端运行态 DTO 收敛为首页稳定读取的字段，缺失值统一降级为空状态。
 */
function toDashboardRuntimeFields(summary: WebUiRuntimeSummary | null): WebUiDashboardRuntimeFields {
  const recentPushRecords = summarizeRecentPushRecords(summary?.recentPushRecords ?? [])
  return {
    appVersion: summary?.appVersion || '--',
    lifecycleState: displayLifecycleState(summary?.lifecycleState),
    uptimeSeconds: summary?.uptimeSeconds ?? null,
    startedAtEpochMillis: summary?.host?.startedAtEpochMillis ?? null,
    systemTimeEpochMillis: summary?.host?.systemTimeEpochMillis ?? null,
    systemLoadAverage: summary?.host?.systemLoadAverage ?? null,
    cpuUsagePercent: summary?.host?.cpuUsagePercent ?? null,
    memoryUsagePercent: summary?.host?.memory?.usagePercent ?? null,
    storageUsagePercent: summary?.host?.storage?.usagePercent ?? null,
    storageUsedBytes: summary?.host?.storage?.usedBytes ?? null,
    storageTotalBytes: summary?.host?.storage?.totalBytes ?? null,
    accountLoggedIn: summary?.account?.loggedIn ?? null,
    accountUid: summary?.account?.uid ?? null,
    lifecycleTone: resolveLifecycleTone(summary?.lifecycleState),
    accountTone: resolveBooleanStatusTone(summary?.account?.loggedIn, 'emerald'),
    platformReady: summary?.platformReady ?? null,
    webSocketConnected: summary?.webSocket?.connected ?? null,
    webSocketTone: resolveBooleanStatusTone(summary?.webSocket?.connected, 'sky'),
    todayPushTotal: summary?.todayPushStats?.total ?? null,
    recentPushRecordsCount: recentPushRecords.length,
    recentPushRecords,
  }
}

/**
 * 生命周期色条按运行健康度映射：运行中为绿色，启动/停止过渡为蓝色，其余不可服务状态为红色。
 */
function resolveLifecycleTone(value: string | undefined): WebUiDashboardStatusTone {
  const normalized = String(value || '').trim().toUpperCase()
  if (normalized === 'RUNNING') return 'emerald'
  if (normalized === 'STARTING' || normalized === 'STOPPING') return 'sky'
  return 'rose'
}

/**
 * 二态健康字段缺失或为 false 时统一按异常色处理，避免旧响应被误展示为正常状态。
 */
function resolveBooleanStatusTone(value: boolean | undefined, healthyTone: WebUiDashboardStatusTone): WebUiDashboardStatusTone {
  return value ? healthyTone : 'rose'
}

/**
 * 最近推送记录只保留首页展示需要的订阅信息，避免把原始 target 和内部类型继续往下传。
 */
function summarizeRecentPushRecords(records: WebUiRecentPushRecord[]): WebUiDashboardRecentPushRecord[] {
  return records.map((record) => ({
    timestampEpochMillis: record.timestampEpochMillis ?? null,
    typeLabel: sanitizeDashboardText(record.typeLabel || record.type || '--'),
    statusLabel: sanitizeDashboardText(record.statusLabel || '--'),
    subscriptionInfo: extractSubscriptionName(record.summary || '--'),
  }))
}

/**
 * 旧响应可能把群、房间等信息拼进 summary；首页名称列只保留第一个订阅名片段。
 */
function extractSubscriptionName(value: string): string {
  const normalized = sanitizeDashboardText(value)
  if (normalized === '--') return normalized
  return sanitizeDashboardText(normalized.split(/\s*[|｜]\s*/)[0] || normalized)
}

/**
 * 首页展示文本只需要稳定可读的内容，前后空白统一折叠掉即可。
 */
function sanitizeDashboardText(value: string): string {
  return value.trim() || '--'
}

/**
 * 后端运行态枚举转换成用户可读文案，避免首页直接暴露 RUNNING 等内部值。
 */
function displayLifecycleState(value: string | undefined): string {
  const normalized = String(value || '').trim().toUpperCase()
  const labels: Record<string, string> = {
    RUNNING: 'Bot运行中',
    STARTING: 'Bot启动中',
    STOPPED: 'Bot已停止',
    STOPPING: 'Bot停止中',
    FAILED: 'Bot异常',
  }
  return labels[normalized] || value || '--'
}

/**
 * 运行态摘要以轮询方式刷新，默认间隔和旧脚本保持一致。
 */
export function useRuntimeSummary(options: UseRuntimeSummaryOptions = {}) {
  const {pollIntervalMs = 30_000, fetchImpl, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin])
  const [summary, setSummary] = useState<WebUiRuntimeSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const payload = await fetchRuntimeSummary(requestOptions)
      setSummary(payload)
      return payload
    } catch (caughtError) {
      const message = caughtError instanceof Error ? caughtError.message : String(caughtError)
      setError(message)
      throw caughtError
    } finally {
      setLoading(false)
    }
  }, [requestOptions])

  useEffect(() => {
    // 首次刷新排入浏览器任务队列，避免 effect body 直接触发状态级联更新。
    const initialTimer = window.setTimeout(() => {
      void refresh()
    }, 0)
    const timer = window.setInterval(() => {
      void refresh()
    }, pollIntervalMs)
    return () => {
      window.clearTimeout(initialTimer)
      window.clearInterval(timer)
    }
  }, [pollIntervalMs, refresh])

  const dashboard = useMemo(() => toDashboardRuntimeFields(summary), [summary])

  return {summary, dashboard, loading, error, refresh}
}
