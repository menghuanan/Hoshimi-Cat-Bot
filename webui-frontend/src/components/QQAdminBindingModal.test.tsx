import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadQQAdminBinding, startQQAdminBinding, type QQAdminBindingSession } from '../api/qqAdminBinding'
import { QQAdminBindingModal } from './QQAdminBindingModal'

vi.mock('../api/qqAdminBinding', () => ({startQQAdminBinding: vi.fn(), loadQQAdminBinding: vi.fn()}))
const waiting: QQAdminBindingSession = {sessionId: 'binding', code: 'QQ-DEMO', expiresAtEpochMillis: Date.now() + 600_000, phase: 'WAITING'}

describe('QQAdminBindingModal', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks() })
  afterEach(() => vi.useRealTimers())

  /** 绑定已落盘时，配置刷新失败只提示刷新页面，不能再次创建代码或重复成功副作用。 */
  it('keeps binding successful when the settings refresh fails', async () => {
    vi.mocked(startQQAdminBinding).mockResolvedValue(waiting)
    vi.mocked(loadQQAdminBinding).mockResolvedValue({...waiting, phase: 'SUCCEEDED', code: null})
    const onSucceeded = vi.fn().mockRejectedValue(new Error('offline'))
    render(<QQAdminBindingModal onClose={vi.fn()} onSucceeded={onSucceeded} />)
    await act(() => vi.advanceTimersByTimeAsync(2_000))
    expect(screen.getByText('绑定完成，管理员已保存。')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('绑定已完成，请刷新页面查看最新配置')
    await act(() => vi.advanceTimersByTimeAsync(20_000))
    expect(onSucceeded).toHaveBeenCalledTimes(1)
    expect(startQQAdminBinding).toHaveBeenCalledTimes(1)
    expect(loadQQAdminBinding).toHaveBeenCalledTimes(1)
  })

  /** 完成状态只通知一次并停止轮询，弹窗沿用 Portal 和键盘关闭能力。 */
  it('shows instructions and stops polling after success', async () => {
    vi.mocked(startQQAdminBinding).mockResolvedValue(waiting)
    vi.mocked(loadQQAdminBinding).mockResolvedValue({...waiting, phase: 'SUCCEEDED', code: null})
    const onSucceeded = vi.fn()
    const onClose = vi.fn()
    const {container} = render(<QQAdminBindingModal onClose={onClose} onSucceeded={onSucceeded} />)
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(screen.getByText('请在QQ内添加bot好友后发送以下代码：')).toBeInTheDocument()
    expect(screen.getByText('QQ-DEMO')).toBeInTheDocument()
    expect(container.querySelector('[role="dialog"]')).toBeNull()
    await act(() => vi.advanceTimersByTimeAsync(2_000))
    expect(screen.getByText('绑定完成，管理员已保存。')).toBeInTheDocument()
    expect(screen.queryByText('QQ-DEMO')).toBeNull()
    await act(() => vi.advanceTimersByTimeAsync(20_000))
    expect(loadQQAdminBinding).toHaveBeenCalledTimes(1)
    expect(onSucceeded).toHaveBeenCalledTimes(1)
    fireEvent.keyDown(document, {key: 'Escape'})
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  /** 临时网络失败保留会话并重试原 ID，不重复创建绑定码。 */
  it('retries a transient read failure in the same session', async () => {
    vi.mocked(startQQAdminBinding).mockResolvedValue(waiting)
    vi.mocked(loadQQAdminBinding).mockRejectedValueOnce(new Error('temporary')).mockResolvedValueOnce({...waiting, phase: 'SUCCEEDED', code: null})
    render(<QQAdminBindingModal onClose={vi.fn()} onSucceeded={vi.fn()} />)
    await act(() => vi.advanceTimersByTimeAsync(2_000))
    expect(screen.getByRole('alert')).toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(2_000))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(startQQAdminBinding).toHaveBeenCalledTimes(1)
    expect(loadQQAdminBinding).toHaveBeenLastCalledWith('binding')
  })

  /** 卸载后即使创建请求才返回，也不能触发成功副作用或残留 timer。 */
  it('ignores a late response after closing and restores focus', async () => {
    let resolve!: (value: QQAdminBindingSession) => void
    vi.mocked(startQQAdminBinding).mockReturnValue(new Promise((done) => { resolve = done }))
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const succeeded = vi.fn()
    const {unmount} = render(<QQAdminBindingModal onClose={vi.fn()} onSucceeded={succeeded} />)
    await act(() => vi.advanceTimersByTimeAsync(0))
    unmount()
    expect(document.activeElement).toBe(trigger)
    await act(async () => resolve({...waiting, phase: 'SUCCEEDED'}))
    await act(() => vi.advanceTimersByTimeAsync(20_000))
    expect(succeeded).not.toHaveBeenCalled()
    expect(loadQQAdminBinding).not.toHaveBeenCalled()
    trigger.remove()
  })
})
