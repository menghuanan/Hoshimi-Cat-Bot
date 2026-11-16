import { useId, useState } from 'react'
import { refreshPlatformGroups, savePlatformGroupAlias } from '../../api/platformGroups'
import type { PlatformGroupsState } from '../../hooks/usePlatformGroups'

type GroupTargetPickerProps = {directory: PlatformGroupsState, label?: string}

/** 官方 QQ 群选择共用于新增与编辑：名称在左、OpenID 在右，支持搜索、多选和本地备注。 */
export function GroupTargetPicker({directory, label = '目标群聊'}: GroupTargetPickerProps) {
  const {data, error, selected, select} = directory
  const panelId = useId()
  const [expanded, setExpanded] = useState(false)
  const [query, setQuery] = useState('')
  const [editingId, setEditingId] = useState('')
  const [alias, setAlias] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const items = (data?.items || []).filter((item) => `${item.name} ${item.alias} ${item.openId}`.toLowerCase().includes(query.toLowerCase()))

  /** 管理操作由按钮触发，不提交外层订阅表单；错误就地展示并保留原选择。 */
  const runAction = async (action: () => Promise<unknown>, success: string) => {
    setBusy(true); setMessage('')
    try { await action(); setMessage(success); setEditingId('') }
    catch (failure) { setMessage(failure instanceof Error ? failure.message : '操作失败，请重试') }
    finally { setBusy(false) }
  }

  return (
    <div className="grid min-w-0 gap-2 text-sm">
      <span className="font-medium text-slate-700">{label}</span>
      <button type="button" aria-label={label} aria-expanded={expanded} aria-controls={panelId}
        onClick={() => setExpanded(!expanded)} disabled={!data?.generation}
        className="flex min-w-0 items-center justify-between gap-2 rounded-lg border border-slate-300 bg-white px-3 py-2 text-left text-slate-700 disabled:opacity-50">
        <span className="truncate">{selected.length ? `已选择 ${selected.length} 个群聊` : '请选择群聊（可多选）'}</span><span aria-hidden="true">{expanded ? '▴' : '▾'}</span>
      </button>
      {expanded ? (
        <div id={panelId} className="rounded-lg border border-slate-200 bg-white p-2 shadow-sm" onKeyDown={(event) => { if (event.key === 'Escape') { event.stopPropagation(); setExpanded(false) } }}>
          <input aria-label="搜索群聊" placeholder="搜索群名、备注或 OpenID" value={query} onChange={(event) => setQuery(event.target.value)} className="mb-2 w-full rounded-lg border border-slate-300 px-3 py-2" />
          <div className="max-h-56 space-y-1 overflow-y-auto" role="group" aria-label="可选群聊">
            {items.map((item) => (
              <div key={item.subject} className="rounded-lg border border-slate-100 p-2">
                <label className="flex min-w-0 cursor-pointer items-start gap-2">
                  <input type="checkbox" className="mt-1" checked={selected.includes(item.subject)} disabled={!item.joined || (selected.length >= 100 && !selected.includes(item.subject))}
                    onChange={(event) => select(event.target.checked ? [...selected, item.subject] : selected.filter((value) => value !== item.subject))} />
                  <span className="min-w-0 flex-1 break-words text-slate-700">{item.alias || item.name || '未获取群名'}
                    <span className="block text-xs text-slate-500">{!item.joined ? '已退群' : item.acceptsProactive === false ? '群通知已关闭' : '已发现'}{item.alias ? ' · 备注名' : ''}</span>
                  </span>
                  <span className="max-w-[55%] break-all text-right font-mono text-xs text-slate-500">{item.openId}</span>
                </label>
                {editingId === item.openId ? (
                  <div className="mt-2 flex flex-wrap gap-2">
                    <input aria-label="群聊备注" maxLength={80} value={alias} onChange={(event) => setAlias(event.target.value)} className="min-w-0 flex-1 rounded border border-slate-300 px-2 py-1" />
                    <button type="button" disabled={busy} onClick={() => void runAction(() => savePlatformGroupAlias(data!.generation, item.openId, alias), '备注已保存，列表将自动更新')} className="text-xs text-slate-700">保存备注</button>
                    <button type="button" onClick={() => setEditingId('')} className="text-xs text-slate-500">取消</button>
                  </div>
                ) : <button type="button" onClick={() => { setEditingId(item.openId); setAlias(item.alias) }} className="mt-1 text-xs text-slate-500">设置备注</button>}
              </div>
            ))}
            {!items.length ? <p className="p-3 text-center text-slate-500">{query ? '没有匹配的群聊' : '暂无已发现的群聊，请在目标群中 @机器人发送一条消息。'}</p> : null}
          </div>
        </div>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500">
        <span>仅显示本机已发现的群；最多选择 100 个。</span>
        <button type="button" disabled={busy || !data?.generation} onClick={() => void runAction(() => refreshPlatformGroups(data!.generation), '已安排刷新已知群信息')} className="underline disabled:opacity-50">刷新群信息</button>
      </div>
      {data?.nameStatus === 'FORBIDDEN' ? <p className="text-xs text-amber-700">当前机器人无群名查询权限，可设置备注名。</p> : null}
      {data && !data.connected ? <p className="text-xs text-amber-700">机器人尚未连接，暂显示本地记录。</p> : null}
      {data?.storageError ? <p role="alert" className="text-xs text-rose-700">{data.storageError}</p> : null}
      {error ? <p role="alert" className="text-xs text-rose-700">{error}</p> : null}
      {message ? <p role="status" className="text-xs text-slate-600">{message}</p> : null}
    </div>
  )
}
