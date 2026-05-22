import { useState, type FormEvent } from 'react'

type SubscriptionModalProps = {
  open: boolean
  pending: boolean
  onClose: () => void
  onSubmit: (payload: Record<string, unknown>) => Promise<void>
}

/**
 * 新增订阅弹窗集中处理 dynamic、bangumi、group 三种输入模式。
 */
export function SubscriptionModal({open, pending, onClose, onSubmit}: SubscriptionModalProps) {
  const [type, setType] = useState('dynamic')
  const [uid, setUid] = useState('')
  const [targetGroup, setTargetGroup] = useState('')
  const [bangumiId, setBangumiId] = useState('')
  const [bangumiTarget, setBangumiTarget] = useState('')
  const [groupName, setGroupName] = useState('')
  const [groupUid, setGroupUid] = useState('')
  const [groupTarget, setGroupTarget] = useState('')

  if (!open) {
    return null
  }

  /**
   * 表单提交只返回当前模式需要的字段，确认密码由 useSubscriptions 统一补充。
   */
  const submitForm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (type === 'group') {
      await onSubmit({type, groupName, groupUid, groupTarget})
      return
    }
    if (type === 'bangumi') {
      await onSubmit({type, bangumiId, bangumiTarget})
      return
    }
    await onSubmit({type, uid, targetGroup})
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/50 px-4 py-6" role="presentation" onMouseDown={onClose}>
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="subscription-create-title"
        className="w-full max-w-2xl rounded-lg border border-slate-200 bg-white p-5 shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between gap-3">
          <h3 id="subscription-create-title" className="text-base font-semibold text-slate-950">新增订阅</h3>
          <button type="button" onClick={onClose} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700">关闭新增订阅</button>
        </div>
        <form className="grid gap-4" onSubmit={submitForm}>
          <label className="grid gap-1 text-sm font-medium text-slate-700">
            <span>订阅类型</span>
            <select value={type} onChange={(event) => setType(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
              <option value="dynamic">动态</option>
              <option value="bangumi">番剧</option>
              <option value="group">分组</option>
            </select>
          </label>
          {type === 'group' ? (
            <div className="grid gap-3 md:grid-cols-3">
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>分组名称</span>
                <input value={groupName} onChange={(event) => setGroupName(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>分组 UID</span>
                <input value={groupUid} onChange={(event) => setGroupUid(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>分组目标群</span>
                <input value={groupTarget} onChange={(event) => setGroupTarget(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
            </div>
          ) : null}
          {type === 'bangumi' ? (
            <div className="grid gap-3 md:grid-cols-2">
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>番剧 ID</span>
                <input value={bangumiId} onChange={(event) => setBangumiId(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>番剧目标群</span>
                <input value={bangumiTarget} onChange={(event) => setBangumiTarget(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
            </div>
          ) : null}
          {type === 'dynamic' ? (
            <div className="grid gap-3 md:grid-cols-2">
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>UID</span>
                <input value={uid} onChange={(event) => setUid(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>目标群聊</span>
                <input value={targetGroup} onChange={(event) => setTargetGroup(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
            </div>
          ) : null}
          <div className="flex justify-end gap-3">
            <button type="button" onClick={onClose} className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700">取消</button>
            <button type="submit" disabled={pending} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-400">确认新增</button>
          </div>
        </form>
      </section>
    </div>
  )
}
