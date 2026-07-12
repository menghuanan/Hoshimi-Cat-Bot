import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { cancelBiliLogin, fetchBiliLoginSession, startBiliLogin } from '../api/biliLogin'
import type { WebUiJsonRequestOptions } from '../api/http'
import { readSessionPassword } from '../auth/sessionCredential'
import type { WebUiBiliLoginSession } from '../types/biliLogin'

type UseBiliQrLoginOptions = WebUiJsonRequestOptions & {
  pollIntervalMs?: number
  successCloseDelayMs?: number
  onSucceeded?: () => void | Promise<void>
  onExpired?: (message: string) => void | Promise<void>
}

/** 终态集中判断，确保失败、过期和取消不会继续创建轮询 timer。 */
function isTerminal(session: WebUiBiliLoginSession): boolean {
  return ['SUCCEEDED', 'EXPIRED', 'FAILED', 'CANCELLED'].includes(session.phase)
}

/**
 * 二维码登录 hook 管理弹窗、轮询、取消和迟到响应隔离，页面只消费稳定状态与命令。
 */
export function useBiliQrLogin(options: UseBiliQrLoginOptions = {}) {
  const {
    fetchImpl,
    redirectToLogin,
    pollIntervalMs = 3_000,
    successCloseDelayMs = 1_500,
    onSucceeded,
    onExpired,
  } = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({fetchImpl, redirectToLogin}), [fetchImpl, redirectToLogin])
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [session, setSession] = useState<WebUiBiliLoginSession | null>(null)
  const [error, setError] = useState('')
  const sessionRef = useRef<WebUiBiliLoginSession | null>(null)
  const generationRef = useRef(0)
  const pollInFlightGenerationRef = useRef<number | null>(null)
  const succeededGenerationRef = useRef<number | null>(null)
  const pollTimerRef = useRef<number | null>(null)
  const successTimerRef = useRef<number | null>(null)

  /** 所有 timer 统一回收，关闭、重试和卸载不会遗留旧轮询。 */
  const clearTimers = useCallback(() => {
    if (pollTimerRef.current !== null) window.clearInterval(pollTimerRef.current)
    if (successTimerRef.current !== null) window.clearTimeout(successTimerRef.current)
    pollTimerRef.current = null
    successTimerRef.current = null
  }, [])

  /** 写入会话时同步 ref，保证同一 act/事件循环内的关闭读取最新 ID。 */
  const installSession = useCallback((next: WebUiBiliLoginSession | null) => {
    sessionRef.current = next
    setSession(next)
  }, [])

  /** 超时终态通知页面层保留反馈，回调失败不得覆盖协调器返回的真实状态。 */
  const notifyExpired = useCallback((message: string) => {
    void Promise.resolve().then(() => onExpired?.(message)).catch(() => undefined)
  }, [onExpired])

  /** 成功反馈完成后才清空图片并关闭，给用户留下明确的终态确认。 */
  const completeSuccess = useCallback((generation: number) => {
    // 同一登录代际的成功副作用只能执行一次，避免重复刷新、Toast 和关闭 timer。
    if (succeededGenerationRef.current === generation) return
    succeededGenerationRef.current = generation
    // 成功回调属于页面级附加副作用；同步异常与异步拒绝都不得逃逸为全局未处理错误。
    void Promise.resolve().then(() => onSucceeded?.()).catch(() => undefined)
    successTimerRef.current = window.setTimeout(() => {
      if (generationRef.current !== generation) return
      installSession(null)
      setOpen(false)
      setError('')
    }, successCloseDelayMs)
  }, [installSession, onSucceeded, successCloseDelayMs])

  /** 单次轮询合并创建响应中的图片，GET 返回 null 时不会让二维码闪烁消失。 */
  const pollSession = useCallback(async (sessionId: string, generation: number) => {
    // interval 只负责触发节奏；慢请求尚未完成时跳过 tick，保证同一会话最多一个 GET 在途。
    if (pollInFlightGenerationRef.current === generation) return
    pollInFlightGenerationRef.current = generation
    try {
      const polled = await fetchBiliLoginSession(sessionId, requestOptions)
      if (generationRef.current !== generation) return
      const next = {
        ...polled,
        qrImageBase64: polled.qrImageBase64 || sessionRef.current?.qrImageBase64 || null,
      }
      // 成功 GET 证明临时轮询故障已经恢复，旧错误不得继续遮挡当前会话。
      setError('')
      installSession(next)
      if (next.phase === 'SUCCEEDED') {
        if (pollTimerRef.current !== null) window.clearInterval(pollTimerRef.current)
        pollTimerRef.current = null
        completeSuccess(generation)
      } else if (isTerminal(next)) {
        if (pollTimerRef.current !== null) window.clearInterval(pollTimerRef.current)
        pollTimerRef.current = null
        // 二维码过期需要在弹窗之外保留页面反馈，其他终态继续使用既有局部文案。
        if (next.phase === 'EXPIRED') notifyExpired(next.message)
      }
    } catch (caughtError) {
      if (generationRef.current !== generation) return
      setError(caughtError instanceof Error ? caughtError.message : String(caughtError))
    } finally {
      // 旧代际的迟到 finally 不得清除后来会话已经取得的 in-flight 闸门。
      if (pollInFlightGenerationRef.current === generation) {
        pollInFlightGenerationRef.current = null
      }
    }
  }, [completeSuccess, installSession, notifyExpired, requestOptions])

  /** 创建新会话前使旧请求代际失效，随后从当前内存凭据完成高风险确认。 */
  const openLogin = useCallback(async () => {
    clearTimers()
    const generation = generationRef.current + 1
    generationRef.current = generation
    pollInFlightGenerationRef.current = null
    succeededGenerationRef.current = null
    setOpen(true)
    setLoading(true)
    setError('')
    installSession(null)
    try {
      const created = await startBiliLogin(readSessionPassword(), requestOptions)
      if (generationRef.current !== generation) {
        // 关闭发生在创建响应之前时，迟到会话仍需立即释放，不能只丢弃本地结果。
        if (created.phase !== 'COMMITTING' && !isTerminal(created)) {
          await cancelBiliLogin(created.sessionId, requestOptions).catch(() => undefined)
        }
        return
      }
      installSession(created)
      if (created.phase === 'SUCCEEDED') {
        completeSuccess(generation)
      } else if (!isTerminal(created)) {
        pollTimerRef.current = window.setInterval(() => {
          void pollSession(created.sessionId, generation)
        }, pollIntervalMs)
      } else if (created.phase === 'EXPIRED') {
        // 创建响应若已经进入过期终态，同样必须走页面级反馈，不能只停留在弹窗文案。
        notifyExpired(created.message)
      }
    } catch (caughtError) {
      if (generationRef.current !== generation) return
      setError(caughtError instanceof Error ? caughtError.message : String(caughtError))
    } finally {
      if (generationRef.current === generation) setLoading(false)
    }
  }, [clearTimers, completeSuccess, installSession, notifyExpired, pollIntervalMs, pollSession, requestOptions])

  /** 等待态关闭先让本地请求失效，再尽力取消后端会话；提交态保持弹窗不变。 */
  const closeLogin = useCallback(async () => {
    const current = sessionRef.current
    if (current?.phase === 'COMMITTING') return
    generationRef.current += 1
    pollInFlightGenerationRef.current = null
    succeededGenerationRef.current = null
    clearTimers()
    installSession(null)
    setOpen(false)
    setLoading(false)
    setError('')
    if (current && !isTerminal(current)) {
      try {
        await cancelBiliLogin(current.sessionId, requestOptions)
      } catch {
        // 关闭动作保持本地确定性；后端失败会由 3 分钟 TTL 最终释放会话。
      }
    }
  }, [clearTimers, installSession, requestOptions])

  /** 活动会话的临时错误原位重试 GET；只有无活动会话或终态才重新创建二维码。 */
  const retryLogin = useCallback(async () => {
    const current = sessionRef.current
    if (current && !isTerminal(current)) {
      setError('')
      await pollSession(current.sessionId, generationRef.current)
      return
    }
    await openLogin()
  }, [openLogin, pollSession])

  useEffect(() => {
    return () => {
      const current = sessionRef.current
      generationRef.current += 1
      pollInFlightGenerationRef.current = null
      succeededGenerationRef.current = null
      clearTimers()
      if (current && current.phase !== 'COMMITTING' && !isTerminal(current)) {
        void cancelBiliLogin(current.sessionId, requestOptions).catch(() => undefined)
      }
      sessionRef.current = null
    }
  }, [clearTimers, requestOptions])

  return {
    open,
    loading,
    session,
    error,
    openLogin,
    closeLogin,
    retryLogin,
  }
}
