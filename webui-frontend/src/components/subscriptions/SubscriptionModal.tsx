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
  const [errorMessage, setErrorMessage] = useState('')

  if (!open) {
    return null
  }

  /**
   * 表单提交只返回当前模式需要的字段，确认密码由 useSubscriptions 统一补充。
   */
  const submitForm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const errors = validateSubscriptionDraft({
      type,
      uid,
      targetGroup,
      bangumiId,
      bangumiTarget,
      groupName,
      groupUid,
      groupTarget,
    })
    if (errors.length > 0) {
      setErrorMessage(errors.join('；'))
      return
    }
    setErrorMessage('')
    if (type === 'group') {
      await onSubmit({type, groupName: groupName.trim(), groupUid: groupUid.trim(), groupTarget: groupTarget.trim()})
      return
    }
    if (type === 'bangumi') {
      await onSubmit({type, bangumiId: bangumiId.trim().toLowerCase(), bangumiTarget: bangumiTarget.trim()})
      return
    }
    await onSubmit({type, uid: uid.trim(), targetGroup: targetGroup.trim()})
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/50 px-4 py-6" role="presentation">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="subscription-create-title"
        className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-slate-200 bg-white p-5 shadow-2xl"
      >
        <div className="mb-4">
          <h3 id="subscription-create-title" className="text-base font-semibold text-slate-950">新增订阅</h3>
        </div>
        <form className="grid gap-4" onSubmit={submitForm}>
          {errorMessage ? (
            <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
              {errorMessage}
            </div>
          ) : null}
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

type SubscriptionDraftValues = {
  type: string
  uid: string
  targetGroup: string
  bangumiId: string
  bangumiTarget: string
  groupName: string
  groupUid: string
  groupTarget: string
}

/**
 * 新增订阅先做浏览器侧格式检查，避免明显错误值进入密码确认和后端写入链路。
 */
function validateSubscriptionDraft(draft: SubscriptionDraftValues): string[] {
  if (draft.type === 'group') {
    return [
      ...validateRequired('分组名称', draft.groupName),
      ...validateOptionalPositiveInteger('分组 UID', draft.groupUid),
      ...validateOptionalPositiveInteger('分组目标群', draft.groupTarget),
    ]
  }
  if (draft.type === 'bangumi') {
    return [
      ...validateBangumiId(draft.bangumiId),
      ...validatePositiveInteger('番剧目标群', draft.bangumiTarget),
    ]
  }
  return [
    ...validatePositiveInteger('UID', draft.uid),
    ...validatePositiveInteger('目标群聊', draft.targetGroup),
  ]
}

/**
 * 必填字段统一使用同一文案，保持新增订阅三种模式的错误提示一致。
 */
function validateRequired(label: string, value: string): string[] {
  return value.trim() ? [] : [`${label}必须填写`]
}

/**
 * 必填数字字段仅接受正整数字符串，提前对齐后端 subject 归一化约束。
 */
function validatePositiveInteger(label: string, value: string): string[] {
  const text = value.trim()
  if (!text) return [`${label}必须填写`]
  return /^\d+$/.test(text) && Number.parseInt(text, 10) > 0 ? [] : [`${label}必须是正整数`]
}

/**
 * 分组新增的 UID 和目标群仍保持可选，但填写时必须满足后端可解释的数字格式。
 */
function validateOptionalPositiveInteger(label: string, value: string): string[] {
  const text = value.trim()
  if (!text) return []
  return /^\d+$/.test(text) && Number.parseInt(text, 10) > 0 ? [] : [`${label}必须是正整数`]
}

/**
 * 番剧订阅沿用后端支持的 ss/md/ep 标识格式，避免先输密码后才返回格式错误。
 */
function validateBangumiId(value: string): string[] {
  const text = value.trim().toLowerCase()
  if (!text) return ['番剧ID必须填写']
  return /^(ss|md|ep)\d+$/.test(text) ? [] : ['番剧ID必须以 ss、md 或 ep 开头']
}
