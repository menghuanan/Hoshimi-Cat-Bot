import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchRuntimeSummary } from '../api/runtime'
import type { WebUiJsonRequestOptions } from '../api/http'
import type { WebUiRuntimeSummary } from '../types/runtime'

type UseRuntimeSummaryOptions = WebUiJsonRequestOptions & {
  pollIntervalMs?: number
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

  return {summary, loading, error, refresh}
}
