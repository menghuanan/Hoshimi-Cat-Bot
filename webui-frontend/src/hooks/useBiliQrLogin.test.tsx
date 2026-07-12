import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useBiliQrLogin } from './useBiliQrLogin'

const response = (status: number, payload: unknown) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => payload,
})

describe('useBiliQrLogin', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  /** hook 必须按后端状态轮询，并在成功反馈后自动清空二维码关闭弹窗。 */
  it('polls until success and closes after the success delay', async () => {
    vi.useFakeTimers()
    const onSucceeded = vi.fn()
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(201, {
        sessionId: 'session-1', phase: 'WAITING_FOR_SCAN', expiresAtEpochMillis: Date.now() + 180_000,
        message: '等待扫码', qrImageBase64: 'AQID',
      }))
      .mockResolvedValueOnce(response(200, {
        sessionId: 'session-1', phase: 'WAITING_FOR_CONFIRMATION', expiresAtEpochMillis: Date.now() + 180_000,
        message: '已扫码，等待确认',
      }))
      .mockResolvedValueOnce(response(200, {
        sessionId: 'session-1', phase: 'SUCCEEDED', expiresAtEpochMillis: Date.now() + 180_000,
        message: 'BiliBili 登录成功',
      }))
    const {result} = renderHook(() => useBiliQrLogin({
      fetchImpl,
      pollIntervalMs: 10,
      successCloseDelayMs: 10,
      onSucceeded,
    }))

    await act(async () => {
      await result.current.openLogin()
    })
    expect(result.current.open).toBe(true)
    expect(result.current.session?.qrImageBase64).toBe('AQID')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
    })
    expect(result.current.session?.phase).toBe('WAITING_FOR_CONFIRMATION')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
    })
    expect(result.current.session?.phase).toBe('SUCCEEDED')
    expect(onSucceeded).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
    })
    expect(result.current.open).toBe(false)
    expect(result.current.session).toBeNull()
  })

  /** 二维码等待超时必须通知页面层，弹窗之外仍能留下明确反馈。 */
  it('notifies the page when polling reaches the expired phase', async () => {
    vi.useFakeTimers()
    const onExpired = vi.fn()
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(201, {
        sessionId: 'expired-session', phase: 'WAITING_FOR_SCAN', expiresAtEpochMillis: Date.now() + 180_000,
        message: '等待扫码', qrImageBase64: 'AQID',
      }))
      .mockResolvedValueOnce(response(200, {
        sessionId: 'expired-session', phase: 'EXPIRED', expiresAtEpochMillis: Date.now(),
        message: '登录超时，请重新登录',
      }))
    const {result} = renderHook(() => useBiliQrLogin({
      fetchImpl,
      pollIntervalMs: 10,
      onExpired,
    }))

    await act(async () => {
      await result.current.openLogin()
      await vi.advanceTimersByTimeAsync(10)
      await Promise.resolve()
    })

    expect(result.current.session?.phase).toBe('EXPIRED')
    expect(onExpired).toHaveBeenCalledOnce()
    expect(onExpired).toHaveBeenCalledWith('登录超时，请重新登录')
  })

  /** 页面级成功副作用失败时必须由 hook 消费拒绝，不能形成未处理 Promise rejection。 */
  it('contains a rejected success callback while preserving the successful session', async () => {
    const onSucceeded = vi.fn().mockRejectedValue(new Error('runtime refresh failed'))
    const fetchImpl = vi.fn().mockResolvedValue(response(201, {
      sessionId: 'session-success', phase: 'SUCCEEDED', expiresAtEpochMillis: Date.now() + 180_000,
      message: 'BiliBili 登录成功', qrImageBase64: 'AQID',
    }))
    const {result} = renderHook(() => useBiliQrLogin({
      fetchImpl,
      successCloseDelayMs: 100_000,
      onSucceeded,
    }))

    await act(async () => {
      await result.current.openLogin()
      await Promise.resolve()
    })

    expect(onSucceeded).toHaveBeenCalledTimes(1)
    expect(result.current.session?.phase).toBe('SUCCEEDED')
  })

  /** 等待态关闭要取消后端会话，提交态则必须保持弹窗和会话不变。 */
  it('cancels waiting sessions but refuses to close while committing', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(201, {
        sessionId: 'waiting-session', phase: 'WAITING_FOR_SCAN', expiresAtEpochMillis: Date.now() + 180_000,
        message: '等待扫码', qrImageBase64: 'AQID',
      }))
      .mockResolvedValueOnce(response(200, {
        sessionId: 'waiting-session', phase: 'CANCELLED', expiresAtEpochMillis: Date.now() + 180_000,
        message: '登录已取消',
      }))
    const waitingHook = renderHook(() => useBiliQrLogin({fetchImpl, pollIntervalMs: 100_000}))
    await act(async () => {
      await waitingHook.result.current.openLogin()
      await waitingHook.result.current.closeLogin()
    })
    expect(fetchImpl).toHaveBeenLastCalledWith('/api/bili-login/sessions/waiting-session', expect.objectContaining({method: 'DELETE'}))
    expect(waitingHook.result.current.open).toBe(false)

    const committingFetch = vi.fn().mockResolvedValue(response(201, {
      sessionId: 'commit-session', phase: 'COMMITTING', expiresAtEpochMillis: Date.now() + 180_000,
      message: '正在保存登录凭据', qrImageBase64: 'AQID',
    }))
    const committingHook = renderHook(() => useBiliQrLogin({fetchImpl: committingFetch, pollIntervalMs: 100_000}))
    await act(async () => {
      await committingHook.result.current.openLogin()
      await committingHook.result.current.closeLogin()
    })
    await waitFor(() => expect(committingHook.result.current.open).toBe(true))
    expect(committingFetch).toHaveBeenCalledTimes(1)
  })

  /** 创建响应迟到时仍要取消刚生成的后端会话，不能只丢弃本地状态后等待 TTL。 */
  it('cancels a session that is created after the modal was closed', async () => {
    let resolveCreated!: (value: ReturnType<typeof response>) => void
    const createdResponse = new Promise<ReturnType<typeof response>>((resolve) => {
      resolveCreated = resolve
    })
    const fetchImpl = vi.fn()
      .mockReturnValueOnce(createdResponse)
      .mockResolvedValueOnce(response(200, {
        sessionId: 'late-session', phase: 'CANCELLED', expiresAtEpochMillis: Date.now(), message: '登录已取消',
      }))
    const {result} = renderHook(() => useBiliQrLogin({fetchImpl, pollIntervalMs: 100_000}))

    let opening!: Promise<void>
    await act(async () => {
      opening = result.current.openLogin()
    })
    await act(async () => {
      await result.current.closeLogin()
      resolveCreated(response(201, {
        sessionId: 'late-session', phase: 'WAITING_FOR_SCAN', expiresAtEpochMillis: Date.now() + 180_000,
        message: '等待扫码', qrImageBase64: 'AQID',
      }))
      await opening
    })

    expect(fetchImpl).toHaveBeenLastCalledWith('/api/bili-login/sessions/late-session', expect.objectContaining({method: 'DELETE'}))
    expect(result.current.open).toBe(false)
  })

  /** 慢 GET 未完成时不得启动下一轮请求，成功副作用也只能由唯一响应触发一次。 */
  it('serializes slow polling requests and completes success once', async () => {
    vi.useFakeTimers()
    const onSucceeded = vi.fn()
    let resolvePoll!: (value: ReturnType<typeof response>) => void
    const slowPoll = new Promise<ReturnType<typeof response>>((resolve) => {
      resolvePoll = resolve
    })
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(201, {
        sessionId: 'slow-session', phase: 'WAITING_FOR_SCAN', expiresAtEpochMillis: Date.now() + 180_000,
        message: '等待扫码', qrImageBase64: 'AQID',
      }))
      .mockReturnValue(slowPoll)
    const {result} = renderHook(() => useBiliQrLogin({
      fetchImpl,
      pollIntervalMs: 10,
      successCloseDelayMs: 100,
      onSucceeded,
    }))

    await act(async () => {
      await result.current.openLogin()
      await vi.advanceTimersByTimeAsync(30)
    })

    expect(fetchImpl).toHaveBeenCalledTimes(2)

    await act(async () => {
      resolvePoll(response(200, {
        sessionId: 'slow-session', phase: 'SUCCEEDED', expiresAtEpochMillis: Date.now() + 180_000,
        message: 'BiliBili 登录成功',
      }))
      await slowPoll
      await Promise.resolve()
      await vi.advanceTimersByTimeAsync(50)
    })

    expect(onSucceeded).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  /** 临时轮询失败必须保留当前会话，原位重试 GET 成功后清除旧错误且不得重新 POST。 */
  it('retries a transient polling failure in place and clears the stale error', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(201, {
        sessionId: 'retry-session', phase: 'WAITING_FOR_SCAN', expiresAtEpochMillis: Date.now() + 180_000,
        message: '等待扫码', qrImageBase64: 'AQID',
      }))
      .mockRejectedValueOnce(new Error('temporary polling failure'))
      .mockResolvedValueOnce(response(200, {
        sessionId: 'retry-session', phase: 'WAITING_FOR_CONFIRMATION', expiresAtEpochMillis: Date.now() + 180_000,
        message: '已扫码，等待确认',
      }))
    const {result} = renderHook(() => useBiliQrLogin({fetchImpl, pollIntervalMs: 100_000}))

    await act(async () => {
      await result.current.openLogin()
    })
    await act(async () => {
      await result.current.retryLogin()
    })
    expect(result.current.error).toBe('temporary polling failure')
    expect(result.current.session?.sessionId).toBe('retry-session')

    await act(async () => {
      await result.current.retryLogin()
    })

    expect(result.current.error).toBe('')
    expect(result.current.session?.phase).toBe('WAITING_FOR_CONFIRMATION')
    expect(fetchImpl).toHaveBeenCalledTimes(3)
    expect(fetchImpl.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })
})
