import { useMemo, useState } from 'react'
import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { SubscriptionEditorModal } from '../components/subscriptions/SubscriptionEditorModal'
import { SubscriptionModal } from '../components/subscriptions/SubscriptionModal'
import { useSubscriptions } from '../hooks/useSubscriptions'

type SubscriptionTypeFilter = 'all' | 'dynamic' | 'bangumi' | 'group'
type SubscriptionItem = Record<string, unknown>

const subscriptionTypeLabels: Array<{id: SubscriptionTypeFilter, label: string}> = [
  {id: 'all', label: '全部'},
  {id: 'dynamic', label: '动态'},
  {id: 'bangumi', label: '番剧'},
  {id: 'group', label: '分组'},
]

/**
 * 订阅页提供类型筛选、搜索、新增、删除和四类嵌套编辑器入口。
 */
export function SubscriptionsPage() {
  const subscriptionActions = useSubscriptions()
  const {items, loading, saveSubscription, removeSubscription, reload} = subscriptionActions
  const [query, setQuery] = useState('')
  const [typeFilter, setTypeFilter] = useState<SubscriptionTypeFilter>('all')
  const [createOpen, setCreateOpen] = useState(false)
  const [editingItem, setEditingItem] = useState<SubscriptionItem | null>(null)
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')

  const subscriptionItems = items as SubscriptionItem[]
  const filteredItems = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    return subscriptionItems.filter((item) => {
      const matchesType = typeFilter === 'all' || readItemField(item, 'kind') === typeFilter
      const matchesKeyword = !keyword || searchableSubscriptionText(item).toLowerCase().includes(keyword)
      return matchesType && matchesKeyword
    })
  }, [query, subscriptionItems, typeFilter])

  /**
   * 新增订阅提交后刷新列表，确认密码由 hook 统一弹窗获取。
   */
  const submitSubscription = async (payload: Record<string, unknown>) => {
    setPending(true)
    setMessage('')
    try {
      await saveSubscription(payload)
      await reload()
      setCreateOpen(false)
      setMessage('订阅已提交')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '订阅提交失败')
    } finally {
      setPending(false)
    }
  }

  /**
   * 删除入口优先使用后端 id，缺失时回退到兼容字段。
   */
  const deleteItem = async (item: SubscriptionItem) => {
    const itemId = readItemField(item, 'id') || readItemField(item, 'itemId') || readItemField(item, 'uid')
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
      <PageSection
        title="订阅管理"
        description="管理动态、番剧和分组订阅，并编辑过滤器、模板、at全体和主题色。"
        actions={<button type="button" onClick={() => setCreateOpen(true)} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">新增订阅</button>}
      >
        <div className="grid gap-4 md:grid-cols-4">
          <StatusCard label="全部订阅" value={loading ? '--' : subscriptionItems.length} tone="emerald" />
          <StatusCard label="动态" value={countByKind(subscriptionItems, 'dynamic')} tone="sky" />
          <StatusCard label="番剧" value={countByKind(subscriptionItems, 'bangumi')} tone="amber" />
          <StatusCard label="分组" value={countByKind(subscriptionItems, 'group')} tone="rose" />
        </div>
      </PageSection>

      <PageSection
        title="订阅列表"
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            <label className="sr-only" htmlFor="subscription-search">搜索订阅</label>
            <input
              id="subscription-search"
              aria-label="搜索订阅"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="rounded-lg border border-slate-300 px-3 py-2 text-sm"
              placeholder="搜索订阅"
            />
          </div>
        )}
      >
        <div className="flex flex-wrap gap-2">
          {subscriptionTypeLabels.map((filter) => (
            <button
              key={filter.id}
              type="button"
              aria-pressed={typeFilter === filter.id}
              onClick={() => setTypeFilter(filter.id)}
              className={typeFilter === filter.id
                ? 'rounded-lg bg-slate-950 px-3 py-2 text-sm font-semibold text-white'
                : 'rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50'}
            >
              {filter.label}
            </button>
          ))}
        </div>
        <div className="grid gap-3 xl:grid-cols-2">
          {filteredItems.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500">没有匹配的订阅</div>
          ) : filteredItems.map((item, index) => (
            <SubscriptionCard
              key={readItemField(item, 'id') || index}
              item={item}
              pending={pending}
              onEdit={() => setEditingItem(item)}
              onDelete={() => void deleteItem(item)}
            />
          ))}
        </div>
        {message ? <p className="text-sm font-medium text-slate-700">{message}</p> : null}
      </PageSection>

      <SubscriptionModal open={createOpen} pending={pending} onClose={() => setCreateOpen(false)} onSubmit={submitSubscription} />
      <SubscriptionEditorModal item={editingItem} actions={subscriptionActions} onClose={() => setEditingItem(null)} onReload={reload} />
    </div>
  )
}

/**
 * 订阅卡片展示旧 WebUI 关注的目标、过滤器、模板、at全体和主题色摘要。
 */
function SubscriptionCard({item, pending, onEdit, onDelete}: {
  item: SubscriptionItem
  pending: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const title = readItemField(item, 'title') || readItemField(item, 'subject') || readItemField(item, 'uid') || '未命名订阅'
  const targets = readItemArray(item, 'targets')
  const tags = readItemArray(item, 'tags')

  return (
    <article className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="break-words text-sm font-semibold text-slate-950">{title}</h4>
            {tags.map((tag) => <span key={tag} className="rounded-full bg-slate-100 px-2 py-1 text-xs font-medium text-slate-700">{tag}</span>)}
          </div>
          <p className="mt-1 text-sm text-slate-600">{readItemField(item, 'identifierLabel') || `UID: ${readItemField(item, 'sourceId') || '--'}`}</p>
        </div>
        <div className="flex shrink-0 gap-2">
          <button type="button" onClick={onEdit} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50">编辑</button>
          <button type="button" disabled={pending} onClick={onDelete} className="rounded-lg border border-rose-200 px-3 py-1.5 text-sm font-medium text-rose-700 hover:bg-rose-50 disabled:opacity-50">删除</button>
        </div>
      </div>
      <div className="mt-4">
        <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">{readItemField(item, 'targetSectionTitle') || '推送目标'}</p>
        <div className="mt-2 flex flex-wrap gap-2">
          {targets.length > 0 ? targets.map((target) => <span key={target} className="rounded-full bg-emerald-50 px-2 py-1 text-xs font-medium text-emerald-700">{target}</span>) : <span className="text-sm text-slate-500">暂无目标</span>}
        </div>
      </div>
      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <InfoBlock label="过滤器信息" value={formatCount(item, 'filterCount', '过滤器', readItemField(item, 'filterInfo'))} />
        <InfoBlock label="模板信息" value={formatCount(item, 'templateCount', '模板', readItemArray(item, 'templateNames').join('、'))} />
        <InfoBlock label="at全体" value={readItemField(item, 'atAllInfo') || '未开启'} />
        <InfoBlock label="主题色" value={readItemField(item, 'themeColor') || '默认'} />
      </div>
    </article>
  )
}

/**
 * 信息块固定标签和值的排版，避免长摘要挤压卡片操作区。
 */
function InfoBlock({label, value}: {label: string, value: string}) {
  return (
    <div className="rounded-lg border border-slate-100 bg-slate-50 p-3">
      <p className="text-xs font-semibold text-slate-500">{label}</p>
      <p className="mt-1 break-words text-sm font-medium text-slate-800">{value}</p>
    </div>
  )
}

/**
 * 订阅搜索把常用摘要字段和原始 JSON 都纳入匹配，兼容不同快照形态。
 */
function searchableSubscriptionText(item: SubscriptionItem): string {
  return [
    readItemField(item, 'title'),
    readItemField(item, 'identifierLabel'),
    readItemField(item, 'filterInfo'),
    readItemField(item, 'atAllInfo'),
    readItemField(item, 'themeColor'),
    JSON.stringify(item),
  ].join(' ')
}

/**
 * 类型计数优先使用当前列表，避免后端缺少聚合字段时顶部卡片为空。
 */
function countByKind(items: SubscriptionItem[], kind: string): number {
  return items.filter((item) => readItemField(item, 'kind') === kind).length
}

/**
 * 数量摘要优先展示显式说明，缺失时退回到 “N 个类型”。
 */
function formatCount(item: SubscriptionItem, countKey: string, unit: string, fallback: string): string {
  if (fallback) {
    return fallback
  }
  const count = Number(item[countKey] || 0)
  return `${count} 个${unit}`
}

/**
 * 后端订阅快照字段可能随类型不同而变化，页面统一以字符串方式读取可展示字段。
 */
function readItemField(item: SubscriptionItem, key: string): string {
  return String(item[key] || '')
}

/**
 * 数组字段统一转成字符串数组，避免标签和目标渲染直接依赖后端类型。
 */
function readItemArray(item: SubscriptionItem, key: string): string[] {
  const value = item[key]
  return Array.isArray(value) ? value.map(String).filter(Boolean) : []
}
