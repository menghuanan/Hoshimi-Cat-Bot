import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchRuntimeSummary } from '../api/runtime'
import type { WebUiJsonRequestOptions } from '../api/http'
import type { WebUiDashboardRuntimeFields, WebUiRuntimeSummary } from '../types/runtime'

type UseRuntimeSummaryOptions = WebUiJsonRequestOptions & {
  pollIntervalMs?: number
}

/**
 * 将后端运行态 DTO 收敛为首页稳定读取的字段，缺失值统一降级为空状态。
 */
function toDashboardRuntimeFields(summary: WebUiRuntimeSummary | null): WebUiDashboardRuntimeFields {
  return {
    appVersion: summary?.appVersion || '--',
    lifecycleState: summary?.lifecycleState || '--',
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
    platformReady: summary?.platformReady ?? null,
    webSocketConnected: summary?.webSocket?.connected ?? null,
    todayPushTotal: summary?.todayPushStats?.total ?? null,
    recentPushRecordsCount: summary?.recentPushRecords?.length ?? 0,
  }
}

/**
 * 运行态摘要以轮询方式刷新，默认间隔和旧脚本保持一致。
 */
export function useRuntimeSummary(options: UseRuntimeSummaryOptions = {}) {
  const {pollIntervalMs = 30_000, fetchImpl, storage, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    storage,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin, storage])
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
