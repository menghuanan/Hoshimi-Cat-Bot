import { useState } from 'react'
import { PageSection } from '../components/PageSection'
import { SubscriptionEditorModal } from '../components/subscriptions/SubscriptionEditorModal'
import { SubscriptionModal } from '../components/subscriptions/SubscriptionModal'
import { useSubscriptions } from '../hooks/useSubscriptions'

type SubscriptionItem = Record<string, unknown>

/**
 * 订阅页提供新增、删除和四类嵌套编辑器入口。
 */
export function SubscriptionsPage() {
  const subscriptionActions = useSubscriptions()
  const {items, loading, saveSubscription, removeSubscription, reload} = subscriptionActions
  const [createOpen, setCreateOpen] = useState(false)
  const [editingItem, setEditingItem] = useState<SubscriptionItem | null>(null)
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')

  const subscriptionItems = items as SubscriptionItem[]

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
        {loading ? <p className="text-sm text-slate-500">正在加载</p> : null}
      </PageSection>

      <PageSection title="订阅">
        <div className="grid gap-3 xl:grid-cols-2">
          {subscriptionItems.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500">暂无订阅</div>
          ) : subscriptionItems.map((item, index) => (
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
    <div className="subscription-info-block rounded-lg border border-slate-100 bg-slate-50 p-3">
      <p className="text-xs font-semibold text-slate-500">{label}</p>
      <p className="mt-1 break-words text-sm font-medium text-slate-800">{value}</p>
    </div>
  )
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
