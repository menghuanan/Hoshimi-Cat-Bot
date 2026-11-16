import { useState, type FormEvent } from 'react'
import { ModalPortal } from '../ModalPortal'
import { usePlatformGroups } from '../../hooks/usePlatformGroups'
import { GroupTargetPicker } from './GroupTargetPicker'

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
  const directory = usePlatformGroups(open)
  const official = directory.data?.enabled === true

  if (!open) {
    return null
  }

  /**
   * 表单提交只返回当前模式需要的字段，认证字段不进入业务 payload。
   */
  const submitForm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    // 未确认当前平台时禁止提交，避免官方账号的 OpenID 被旧数字路径接收。
    if (!directory.ready) { setErrorMessage('请等待群列表加载或机器人启动完成'); return }
    const errors = validateSubscriptionDraft({
      type,
      uid,
      targetGroup,
      bangumiId,
      bangumiTarget,
      groupName,
      groupUid,
      groupTarget,
      official,
      selectedCount: directory.selected.length,
    })
    if (errors.length > 0) {
      setErrorMessage(errors.join('；'))
      return
    }
    setErrorMessage('')
    // 一次请求提交全部所选群，后端在一个候选快照内原子保存。
    if (official) {
      await onSubmit({type, uid: uid.trim(), groupName: groupName.trim(), groupUid: groupUid.trim(), bangumiId: bangumiId.trim().toLowerCase(), targetGroups: directory.selected, directoryGeneration: directory.data!.generation})
      return
    }
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
    <ModalPortal>
      <div className="modal-overlay fixed inset-0 z-40 flex items-center justify-center px-4 py-6" role="presentation">
        <section
          role="dialog"
          aria-modal="true"
          aria-labelledby="subscription-create-title"
          className="modal-panel max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-slate-200 bg-white p-5 shadow-2xl"
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
                {!official ? <label className="grid gap-1 text-sm font-medium text-slate-700">
                  <span>分组目标群</span>
                  <input value={groupTarget} onChange={(event) => setGroupTarget(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
                </label> : null}
              </div>
            ) : null}
            {type === 'bangumi' ? (
              <div className="grid gap-3 md:grid-cols-2">
                <label className="grid gap-1 text-sm font-medium text-slate-700">
                  <span>番剧 ID</span>
                  <input value={bangumiId} onChange={(event) => setBangumiId(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
                </label>
                {!official ? <label className="grid gap-1 text-sm font-medium text-slate-700">
                  <span>番剧目标群</span>
                  <input value={bangumiTarget} onChange={(event) => setBangumiTarget(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
                </label> : null}
              </div>
            ) : null}
            {type === 'dynamic' ? (
              <div className="grid gap-3 md:grid-cols-2">
                <label className="grid gap-1 text-sm font-medium text-slate-700">
                  <span>UID</span>
                  <input value={uid} onChange={(event) => setUid(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
                </label>
                {!official ? <label className="grid gap-1 text-sm font-medium text-slate-700">
                  <span>目标群聊</span>
                  <input value={targetGroup} onChange={(event) => setTargetGroup(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
                </label> : null}
              </div>
            ) : null}
            {official ? <GroupTargetPicker directory={directory} /> : directory.error ? <p role="alert" className="text-sm text-rose-700">{directory.error}</p> : !directory.data ? <p className="text-sm text-slate-500">正在读取当前平台…</p> : null}
            <div className="flex justify-end gap-3">
              <button type="button" onClick={onClose} className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700">取消</button>
              <button type="submit" disabled={pending || !directory.ready} className="inline-flex items-center gap-2 rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:bg-slate-400">
                {pending ? <span className="button-spinner" aria-hidden="true" /> : null}
                {pending ? '提交中…' : '确认新增'}
              </button>
            </div>
          </form>
        </section>
      </div>
    </ModalPortal>
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
  official: boolean
  selectedCount: number
}

/**
 * 新增订阅先做浏览器侧格式检查，避免明显错误值进入后端写入链路。
 */
function validateSubscriptionDraft(draft: SubscriptionDraftValues): string[] {
  // 官方目标来自目录，不走 OneBot 群号的正整数规则；分组允许先创建空分组。
  if (draft.official) {
    if (draft.type === 'group') return [...validateRequired('分组名称', draft.groupName), ...validateOptionalPositiveInteger('分组 UID', draft.groupUid), ...(draft.groupUid.trim() && !draft.selectedCount ? ['填写分组 UID 时必须至少选择一个群聊'] : [])]
    return [...(draft.type === 'bangumi' ? validateBangumiId(draft.bangumiId) : validatePositiveInteger('UID', draft.uid)), ...(draft.selectedCount ? [] : ['目标群聊必须至少选择一个'])]
  }
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
