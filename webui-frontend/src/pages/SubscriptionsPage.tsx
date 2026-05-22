import { useMemo, useState } from 'react'
import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { useSubscriptions } from '../hooks/useSubscriptions'

/**
 * 订阅页提供列表、搜索、新增和删除入口，所有写操作继续由 hook 追加确认密码。
 */
export function SubscriptionsPage() {
  const {items, loading, saveSubscription, removeSubscription, reload} = useSubscriptions()
  const [query, setQuery] = useState('')
  const [type, setType] = useState('dynamic')
  const [uid, setUid] = useState('')
  const [targetGroup, setTargetGroup] = useState('')
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')

  const filteredItems = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    if (!keyword) {
      return items
    }
    return items.filter((item) => JSON.stringify(item).toLowerCase().includes(keyword))
  }, [items, query])

  /**
   * 新增订阅只提交当前表单字段，确认密码由 useSubscriptions 内部统一获取。
   */
  const submitSubscription = async () => {
    setPending(true)
    setMessage('')
    try {
      await saveSubscription({type, uid, targetGroup})
      setUid('')
      setTargetGroup('')
      await reload()
      setMessage('订阅已提交')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '订阅提交失败')
    } finally {
      setPending(false)
    }
  }

  /**
   * 删除入口优先使用后端 itemId，缺失时回退到 uid/bangumiId 便于兼容不同快照形态。
   */
  const deleteItem = async (item: unknown) => {
    const itemId = readItemField(item, 'itemId') || readItemField(item, 'id') || readItemField(item, 'uid')
    if (!itemId) {
      setMessage('当前条目缺少可删除标识')
      return
    }
    setPending(true)
    setMessage('')
    try {
      await removeSubscription(itemId)
      await reload()
      setMessage('订阅已删除')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '删除失败')
    } finally {
      setPending(false)
    }
  }

  return (
    <div data-page="subscriptions" className="space-y-6">
      <PageSection title="订阅管理" description="新增和删除订阅都会提交 confirmationPassword，和后端高风险写入契约一致。">
        <div className="grid gap-4 md:grid-cols-3">
          <StatusCard label="全部订阅" value={loading ? '--' : items.length} tone="emerald" />
          <StatusCard label="当前筛选" value={filteredItems.length} tone="sky" />
          <StatusCard label="写入状态" value={pending ? '处理中' : '空闲'} tone="amber" />
        </div>
      </PageSection>

      <PageSection title="新增订阅" description="填写最小订阅字段后提交，复杂模板编辑保留在后续页面细化中。">
        <div className="grid gap-3 rounded-lg border border-slate-200 bg-white p-4 shadow-sm lg:grid-cols-[10rem_1fr_1fr_auto]">
          <select value={type} onChange={(event) => setType(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
            <option value="dynamic">订阅</option>
            <option value="bangumi">番剧</option>
            <option value="group">分组</option>
          </select>
          <input value={uid} onChange={(event) => setUid(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" placeholder="UID / 番剧 ID / 分组 UID" />
          <input value={targetGroup} onChange={(event) => setTargetGroup(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" placeholder="目标群聊" />
          <button type="button" disabled={pending} onClick={submitSubscription} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-400">
            添加
          </button>
        </div>
        {message ? <p className="text-sm font-medium text-slate-700">{message}</p> : null}
      </PageSection>

      <PageSection title="订阅列表" actions={<input value={query} onChange={(event) => setQuery(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" placeholder="搜索订阅" />}>
        <div className="grid gap-3 xl:grid-cols-2">
          {filteredItems.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500">暂无订阅</div>
          ) : filteredItems.map((item, index) => (
            <article key={`${readItemField(item, 'id') || index}`} className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold text-slate-950">{readItemField(item, 'subject') || readItemField(item, 'uid') || '未命名订阅'}</p>
                  <p className="mt-1 text-sm text-slate-600">{JSON.stringify(item)}</p>
                </div>
                <button type="button" disabled={pending} onClick={() => void deleteItem(item)} className="rounded-lg border border-rose-200 px-3 py-1.5 text-sm font-medium text-rose-700 hover:bg-rose-50 disabled:opacity-50">
                  删除
                </button>
              </div>
            </article>
          ))}
        </div>
      </PageSection>
    </div>
  )
}

/**
 * 后端订阅快照字段可能随类型不同而变化，页面统一以字符串方式读取可展示字段。
 */
function readItemField(item: unknown, key: string): string {
  if (!item || typeof item !== 'object' || !(key in item)) {
    return ''
  }
  return String((item as Record<string, unknown>)[key] || '')
}
