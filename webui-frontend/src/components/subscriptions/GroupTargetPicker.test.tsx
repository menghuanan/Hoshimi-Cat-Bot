import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SubscriptionModal } from './SubscriptionModal'
import type { PlatformGroupDirectory } from '../../api/platformGroups'
import { buildSubscriptionCreatePayload } from '../../subscriptions/subscriptionPayloads'

/** 纯公开 DTO 模拟群发现结果，覆盖名称缺失、关闭通知与退群三种真实状态。 */
function directory(): PlatformGroupDirectory {
  return {enabled: true, generation: 'generation-a', connected: true, nameStatus: 'FORBIDDEN', stateStatus: 'AVAILABLE', storageError: null, items: [
    {subject: 'qq_official:group:opaque-a', openId: 'opaque-a', name: '群聊甲', alias: '', joined: true, acceptsProactive: true, observedAt: 1, refreshedAt: 2},
    {subject: 'qq_official:group:opaque-b', openId: 'opaque-b', name: '', alias: '我的群', joined: true, acceptsProactive: false, observedAt: 1, refreshedAt: 0},
    {subject: 'qq_official:group:opaque-c', openId: 'opaque-c', name: '退出的群', alias: '', joined: false, acceptsProactive: false, observedAt: 1, refreshedAt: 0},
  ]}
}

describe('official QQ subscription group picker', () => {
  afterEach(() => vi.useRealTimers())

  /** 多选一次提交两个完整 subject；退群禁选，名字和 OpenID 作为独立文本展示。 */
  it('submits multiple opaque targets and supports search and aliases', async () => {
    const data = directory()
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<unknown>>().mockImplementation(async () => ({ok: true, status: 200, json: async () => data}))
    vi.stubGlobal('fetch', fetch)
    const onSubmit = vi.fn<(payload: Record<string, unknown>) => Promise<void>>().mockResolvedValue(undefined)
    render(<SubscriptionModal open pending={false} onClose={() => {}} onSubmit={onSubmit} />)
    const button = await screen.findByRole('button', {name: '目标群聊'})
    fireEvent.click(button)
    expect(screen.getByText('opaque-a')).toBeVisible()
    expect(screen.getByRole('checkbox', {name: /退出的群/})).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox', {name: /群聊甲/}))
    fireEvent.click(screen.getByRole('checkbox', {name: /我的群/}))
    expect(button).toHaveTextContent('已选择 2 个群聊')
    fireEvent.change(screen.getByLabelText('搜索群聊'), {target: {value: 'opaque-b'}})
    expect(screen.queryByText('opaque-a')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', {name: '设置备注'}))
    fireEvent.change(screen.getByLabelText('群聊备注'), {target: {value: '新备注'}})
    fireEvent.click(screen.getByRole('button', {name: '保存备注'}))
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/platform/groups/alias', expect.objectContaining({body: JSON.stringify({generation: 'generation-a', openId: 'opaque-b', alias: '新备注'})})))
    fireEvent.change(screen.getByLabelText('UID'), {target: {value: '123'}})
    fireEvent.click(screen.getByRole('button', {name: '确认新增'}))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit.mock.calls[0]?.[0]).toMatchObject({targetGroups: ['qq_official:group:opaque-a', 'qq_official:group:opaque-b'], directoryGeneration: 'generation-a'})
    expect(buildSubscriptionCreatePayload({type: 'dynamic', uid: '123', targetGroups: ['qq_official:group:opaque-a'], directoryGeneration: 'generation-a'})).toEqual({type: 'dynamic', uid: '123', targetGroups: ['qq_official:group:opaque-a'], directoryGeneration: 'generation-a'})
  })

  /** 账号切换后即使 OpenID 文本相同也清空选择；关闭弹窗后停止轮询。 */
  it('invalidates selection on generation changes and stops polling on close', async () => {
    vi.useFakeTimers()
    const data = directory()
    const fetch = vi.fn(async () => ({ok: true, status: 200, json: async () => ({...data})}))
    vi.stubGlobal('fetch', fetch)
    const onSubmit = vi.fn(async () => {})
    const view = render(<SubscriptionModal open pending={false} onClose={() => {}} onSubmit={onSubmit} />)
    await act(() => vi.advanceTimersByTimeAsync(1))
    fireEvent.click(screen.getByRole('button', {name: '目标群聊'}))
    fireEvent.click(screen.getByRole('checkbox', {name: /群聊甲/}))
    // 已观察到退群的选择不会因再次入群而悄悄恢复。
    data.items[0].joined = false
    await act(() => vi.advanceTimersByTimeAsync(3000))
    data.items[0].joined = true
    await act(() => vi.advanceTimersByTimeAsync(3000))
    expect(screen.getByRole('checkbox', {name: /群聊甲/})).not.toBeChecked()
    fireEvent.click(screen.getByRole('checkbox', {name: /群聊甲/}))
    data.generation = 'generation-b'
    await act(() => vi.advanceTimersByTimeAsync(3000))
    expect(screen.getByRole('checkbox', {name: /群聊甲/})).not.toBeChecked()
    view.rerender(<SubscriptionModal open={false} pending={false} onClose={() => {}} onSubmit={onSubmit} />)
    const calls = fetch.mock.calls.length
    await act(() => vi.advanceTimersByTimeAsync(9000))
    expect(fetch).toHaveBeenCalledTimes(calls)
  })

  /** 无法确认平台时阻止新增，不能把网络错误误认为 OneBot 数字输入模式。 */
  it('blocks submission when directory cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))
    render(<SubscriptionModal open pending={false} onClose={() => {}} onSubmit={async () => {}} />)
    expect(await screen.findByText('群列表加载失败，请稍后重试')).toBeVisible()
    expect(screen.getByRole('button', {name: '确认新增'})).toBeDisabled()
  })
})
