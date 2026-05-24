import { useRef, useState, type FormEvent } from 'react'
import { formatPasswordErrorMessage } from '../../utils/errorMessages'

type EditorAction = 'overview' | 'filters' | 'templates' | 'atall' | 'theme'
type EditorFormMode = 'none' | 'filter' | 'template' | 'atall'
type EditorConfigKind = 'filter' | 'template' | 'atall'
type SubscriptionItem = Record<string, unknown>
type SubscriptionEditorActions = {
  loadFilters: (itemId: string) => Promise<unknown>
  loadTemplates: (itemId: string) => Promise<unknown>
  loadAtAll: (itemId: string) => Promise<unknown>
  loadTheme: (itemId: string) => Promise<unknown>
  saveFilter: (itemId: string, draft: {key: string, kind: string, mode: string, content: string}) => Promise<unknown>
  saveTemplate: (itemId: string, draft: {key: string, type: string, name: string, content: string}) => Promise<unknown>
  saveAtAll: (itemId: string, draft: {type: string, targetGroups: string[]}) => Promise<unknown>
  saveTheme: (itemId: string, color: string) => Promise<unknown>
  removeConfig: (itemId: string, kind: 'filter' | 'template' | 'atall', key: string) => Promise<unknown>
  toggleRandomTemplate: (itemId: string, enabled: boolean) => Promise<unknown>
}
type SubscriptionEditorModalProps = {
  item: SubscriptionItem | null
  actions: SubscriptionEditorActions
  onClose: () => void
  onReload: () => Promise<void>
}

/**
 * 订阅配置编辑弹窗承载过滤器、模板、at全体和主题色四类嵌套编辑入口。
 */
export function SubscriptionEditorModal({item, actions, onClose, onReload}: SubscriptionEditorModalProps) {
  const itemId = item ? readStableSubscriptionId(item) : ''
  const targets = item ? readItemArray(item, 'targets') : []
  const loadSequenceRef = useRef(0)
  const [activeAction, setActiveAction] = useState<EditorAction>('overview')
  const [filters, setFilters] = useState<Record<string, unknown>[]>([])
  const [templates, setTemplates] = useState<Record<string, unknown>[]>([])
  const [randomEnabled, setRandomEnabled] = useState(false)
  const [atAllItems, setAtAllItems] = useState<Record<string, unknown>[]>([])
  const [themeColor, setThemeColor] = useState('')
  const [formMode, setFormMode] = useState<EditorFormMode>('none')
  const [editingDraft, setEditingDraft] = useState<Record<string, unknown> | null>(null)
  const [status, setStatus] = useState('')
  const [statusTone, setStatusTone] = useState<'neutral' | 'success' | 'error'>('neutral')

  if (!item) {
    return null
  }

  /**
   * 异步加载只允许最新一次请求写回 state，防止旧订阅响应覆盖当前订阅。
   */
  const isCurrentLoad = (sequence: number) => sequence === loadSequenceRef.current

  /**
   * 配置面板切换时按需加载对应后端数据，并保留操作按钮常驻。
   */
  const openAction = async (nextAction: EditorAction) => {
    const sequence = ++loadSequenceRef.current
    setStatus('')
    setStatusTone('neutral')
    setFormMode('none')
    setEditingDraft(null)
    setActiveAction(nextAction)
    if (!itemId || nextAction === 'overview') {
      return
    }
    if (nextAction === 'filters') {
      const payload = await actions.loadFilters(itemId) as {filters?: Record<string, unknown>[]}
      if (!isCurrentLoad(sequence)) return
      setFilters(Array.isArray(payload?.filters) ? payload.filters : [])
    }
    if (nextAction === 'templates') {
      const payload = await actions.loadTemplates(itemId) as {templates?: Record<string, unknown>[], randomEnabled?: boolean}
      if (!isCurrentLoad(sequence)) return
      setTemplates(Array.isArray(payload?.templates) ? payload.templates : [])
      setRandomEnabled(Boolean(payload?.randomEnabled))
    }
    if (nextAction === 'atall') {
      const payload = await actions.loadAtAll(itemId) as {items?: Record<string, unknown>[]}
      if (!isCurrentLoad(sequence)) return
      setAtAllItems(Array.isArray(payload?.items) ? payload.items : [])
    }
    if (nextAction === 'theme') {
      const payload = await actions.loadTheme(itemId) as {color?: string}
      if (!isCurrentLoad(sequence)) return
      setThemeColor(String(payload?.color || ''))
    }
  }

  /**
   * 新增表单从空草稿进入，编辑表单则由行内按钮传入当前条目。
   */
  const startForm = (mode: Exclude<EditorFormMode, 'none'>, draft: Record<string, unknown> | null = null) => {
    setStatus('')
    setStatusTone('neutral')
    setEditingDraft(draft)
    setFormMode(mode)
  }

  /**
   * 表单取消回到当前列表面板，不改变已经加载或暂存的后端配置。
   */
  const cancelForm = () => {
    setEditingDraft(null)
    setFormMode('none')
  }

  /**
   * 随机模板切换立即写入，失败时交给状态文案提示并保持当前 UI 状态。
   */
  const toggleRandom = async (enabled: boolean) => {
    if (!itemId) {
      setStatus('当前条目缺少可保存标识')
      setStatusTone('error')
      return
    }
    try {
      await actions.toggleRandomTemplate(itemId, enabled)
      setRandomEnabled(enabled)
      await onReload()
      setStatus(enabled ? '随机模板已开启' : '随机模板已关闭')
      setStatusTone('success')
    } catch (error) {
      setStatus(formatPasswordErrorMessage(error, '切换随机模板失败'))
      setStatusTone('error')
    }
  }

  /**
   * 删除嵌套配置项只使用当前行的 key，避免误删同订阅下其他配置。
   */
  const deleteConfigItem = async (kind: EditorConfigKind, draft: Record<string, unknown>) => {
    const key = readConfigKey(draft)
    if (!itemId || !key) {
      setStatus('当前配置项缺少可删除标识')
      setStatusTone('error')
      return
    }
    try {
      await actions.removeConfig(itemId, kind, key)
      await openAction(actionForConfigKind(kind))
      await onReload()
      setStatus(`${configKindLabel(kind)}已删除`)
      setStatusTone('success')
    } catch (error) {
      setStatus(formatPasswordErrorMessage(error, `删除${configKindLabel(kind)}失败`))
      setStatusTone('error')
    }
  }

  /**
   * 保存过滤器后刷新当前面板，确保列表和订阅卡片都同步更新。
   */
  const submitFilter = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      setStatus('当前条目缺少可保存标识')
      setStatusTone('error')
      return
    }
    try {
      const form = new FormData(event.currentTarget)
      await actions.saveFilter(itemId, {
        key: readItemField(editingDraft || {}, 'key'),
        kind: String(form.get('kind') || 'regex'),
        mode: String(form.get('mode') || 'black'),
        content: String(form.get('content') || '').trim(),
      })
      setFormMode('none')
      setEditingDraft(null)
      await openAction('filters')
      await onReload()
      setStatus('过滤器已保存')
      setStatusTone('success')
    } catch (error) {
      setStatus(formatPasswordErrorMessage(error, '保存过滤器失败'))
      setStatusTone('error')
    }
  }

  /**
   * 保存模板时保留模板正文原样，支持用户主动保存空正文模板。
   */
  const submitTemplate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      setStatus('当前条目缺少可保存标识')
      setStatusTone('error')
      return
    }
    try {
      const form = new FormData(event.currentTarget)
      await actions.saveTemplate(itemId, {
        key: readItemField(editingDraft || {}, 'key'),
        type: String(form.get('type') || 'dynamic'),
        name: String(form.get('name') || '').trim(),
        content: String(form.get('content') || ''),
      })
      setFormMode('none')
      setEditingDraft(null)
      await openAction('templates')
      await onReload()
      setStatus('模板已保存')
      setStatusTone('success')
    } catch (error) {
      setStatus(formatPasswordErrorMessage(error, '保存模板失败'))
      setStatusTone('error')
    }
  }

  /**
   * @全体保存读取多选群聊，保持与旧 WebUI 的多目标编辑能力一致。
   */
  const submitAtAll = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      setStatus('当前条目缺少可保存标识')
      setStatusTone('error')
      return
    }
    try {
      const form = new FormData(event.currentTarget)
      const targetGroups = form.getAll('targetGroups').map(String)
      await actions.saveAtAll(itemId, {
        type: String(form.get('type') || '全部'),
        targetGroups,
      })
      setFormMode('none')
      setEditingDraft(null)
      await openAction('atall')
      await onReload()
      setStatus('@全体已保存')
      setStatusTone('success')
    } catch (error) {
      setStatus(formatPasswordErrorMessage(error, '保存at全体失败'))
      setStatusTone('error')
    }
  }

  /**
   * 主题色保存使用当前输入值，HEX 细节仍由后端二次校验。
   */
  const submitTheme = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      setStatus('当前条目缺少可保存标识')
      setStatusTone('error')
      return
    }
    if (!isValidThemeColor(themeColor)) {
      setStatus('主题颜色必须是 HEX 颜色')
      setStatusTone('error')
      return
    }
    try {
      await actions.saveTheme(itemId, themeColor.trim())
      await onReload()
      setStatus('主题色已保存')
      setStatusTone('success')
    } catch (error) {
      setStatus(formatPasswordErrorMessage(error, '保存主题色失败'))
      setStatusTone('error')
    }
  }

  // 当前打开的表单类型决定列表和表单互斥展示，避免编辑时保留旧摘要。
  const filterFormOpen = formMode === 'filter'
  const templateFormOpen = formMode === 'template'
  const atAllFormOpen = formMode === 'atall'

  return (
    <div data-subscription-editor-overlay className="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/50 px-4 py-6 lg:left-[18rem]" role="presentation">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="subscription-editor-title"
        className="grid max-h-[90vh] w-full max-w-[46rem] gap-4 overflow-y-auto rounded-lg border border-slate-200 bg-white p-5 shadow-2xl lg:grid-cols-[13rem_minmax(0,28rem)] lg:grid-rows-[auto_minmax(0,1fr)]"
      >
        <div className="lg:col-start-1 lg:row-start-1">
          <h3 id="subscription-editor-title" className="text-base font-semibold text-slate-950">编辑订阅配置</h3>
          <p className="mt-1 text-sm text-slate-600">{readItemField(item, 'title') || '未命名订阅'}</p>
        </div>
        <aside className="space-y-3 lg:col-start-1 lg:row-start-2">
          <div className="grid gap-2">
            <button type="button" onClick={() => void openAction('filters')} className={actionButtonClass(activeAction === 'filters')}>编辑过滤器</button>
            <button type="button" onClick={() => void openAction('templates')} className={actionButtonClass(activeAction === 'templates')}>编辑模板</button>
            <button type="button" onClick={() => void openAction('atall')} className={actionButtonClass(activeAction === 'atall')}>编辑at全体</button>
            <button type="button" onClick={() => void openAction('theme')} className={actionButtonClass(activeAction === 'theme')}>编辑主题色</button>
          </div>
          <button type="button" onClick={onClose} className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700">关闭</button>
        </aside>

        <div data-subscription-editor-panel className="min-w-0 w-full space-y-4 lg:col-start-2 lg:row-start-2 lg:max-w-md">
          {activeAction === 'overview' ? <EditorEmptyState text="选择左侧编辑器开始配置" /> : null}
          {activeAction === 'filters' ? (
            <div className="space-y-3">
              {filterFormOpen ? (
                <FilterForm title={editorFormTitle('filter', editingDraft)} draft={editingDraft} onSubmit={submitFilter} onCancel={cancelForm} />
              ) : (
                <>
                  <EditorList items={filters} kind="filter" emptyText="暂无过滤器" onEdit={(draft) => startForm('filter', draft)} onDelete={(draft) => void deleteConfigItem('filter', draft)} />
                  <button type="button" onClick={() => startForm('filter')} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">添加过滤器</button>
                </>
              )}
            </div>
          ) : null}
          {activeAction === 'templates' ? (
            <div className="space-y-3">
              {templateFormOpen ? (
                <TemplateForm title={editorFormTitle('template', editingDraft)} draft={editingDraft} onSubmit={submitTemplate} onCancel={cancelForm} />
              ) : (
                <>
                  <label className="inline-flex items-center gap-2 text-sm font-medium text-slate-700">
                    <input type="checkbox" checked={randomEnabled} onChange={(event) => void toggleRandom(event.target.checked)} />
                    <span>随机模板</span>
                  </label>
                  <EditorList items={templates} kind="template" emptyText="暂无模板" onEdit={(draft) => startForm('template', draft)} onDelete={(draft) => void deleteConfigItem('template', draft)} />
                  <button type="button" onClick={() => startForm('template')} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">添加模板</button>
                </>
              )}
            </div>
          ) : null}
          {activeAction === 'atall' ? (
            <div className="space-y-3">
              {atAllFormOpen ? (
                <AtAllForm targets={targets} draft={editingDraft} onSubmit={submitAtAll} onCancel={cancelForm} />
              ) : (
                <>
                  <EditorList items={atAllItems} kind="atall" emptyText="暂无atall信息" onEdit={(draft) => startForm('atall', draft)} onDelete={(draft) => void deleteConfigItem('atall', draft)} />
                  <button type="button" onClick={() => startForm('atall')} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">添加at全体</button>
                </>
              )}
            </div>
          ) : null}
          {activeAction === 'theme' ? (
            <form className="grid max-w-sm gap-3" onSubmit={submitTheme}>
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                <span>主题颜色</span>
                <input value={themeColor} onChange={(event) => setThemeColor(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <button type="submit" className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">保存主题色</button>
            </form>
          ) : null}
          {status ? (
            <p role={statusTone === 'error' ? 'alert' : 'status'} className={`rounded-lg px-3 py-2 text-sm font-medium ${statusTone === 'success' ? 'bg-emerald-50 text-emerald-700' : statusTone === 'error' ? 'bg-rose-50 text-rose-700' : 'bg-slate-50 text-slate-700'}`}>
              {status}
            </p>
          ) : null}
        </div>
      </section>
    </div>
  )
}

/**
 * 过滤器表单保留旧 WebUI 的类型、模式和内容三组核心字段。
 */
function FilterForm({title, draft, onSubmit, onCancel}: {title?: string, draft: Record<string, unknown> | null, onSubmit: (event: FormEvent<HTMLFormElement>) => void, onCancel: () => void}) {
  return (
    <form className="grid gap-3 rounded-lg border border-slate-200 p-4" onSubmit={onSubmit}>
      {title ? <p className="text-sm font-semibold text-slate-900">{title}</p> : null}
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>过滤类型</span>
        <select name="kind" defaultValue={readItemField(draft || {}, 'kind') || 'regex'} className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
          <option value="regex">正则</option>
          <option value="type">动态类型</option>
        </select>
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>规则模式</span>
        <select name="mode" defaultValue={readItemField(draft || {}, 'mode') || 'black'} className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
          <option value="black">黑名单</option>
          <option value="white">白名单</option>
        </select>
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>规则内容</span>
        <input name="content" defaultValue={readItemField(draft || {}, 'content')} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
      </label>
      <FormButtons submitText="保存过滤器" onCancel={onCancel} />
    </form>
  )
}

/**
 * 模板表单保持类型、名称和正文，正文不做前端重写。
 */
function TemplateForm({title, draft, onSubmit, onCancel}: {title?: string, draft: Record<string, unknown> | null, onSubmit: (event: FormEvent<HTMLFormElement>) => void, onCancel: () => void}) {
  return (
    <form className="grid gap-3 rounded-lg border border-slate-200 p-4" onSubmit={onSubmit}>
      {title ? <p className="text-sm font-semibold text-slate-900">{title}</p> : null}
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>模板类型</span>
        <select name="type" defaultValue={readItemField(draft || {}, 'type') || 'dynamic'} className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
          <option value="dynamic">动态</option>
          <option value="live">直播</option>
          <option value="liveClose">下播</option>
        </select>
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>模板名称</span>
        <input name="name" defaultValue={readItemField(draft || {}, 'name')} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>模板内容</span>
        <textarea name="content" defaultValue={readItemField(draft || {}, 'content')} className="min-h-28 rounded-lg border border-slate-300 px-3 py-2 text-sm" />
      </label>
      <FormButtons submitText="保存模板" onCancel={onCancel} />
    </form>
  )
}

/**
 * @全体表单按订阅目标生成多选项，支持一次选择多个目标群。
 */
function AtAllForm({targets, draft, onSubmit, onCancel}: {targets: string[], draft: Record<string, unknown> | null, onSubmit: (event: FormEvent<HTMLFormElement>) => void, onCancel: () => void}) {
  const selectedGroups = new Set([...readItemArray(draft || {}, 'targetGroups'), ...readItemArray(draft || {}, 'groups')])
  return (
    <form className="grid gap-3 rounded-lg border border-slate-200 p-4" onSubmit={onSubmit}>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>at类型</span>
        <select name="type" defaultValue={readItemField(draft || {}, 'type') || '全部'} className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
          <option value="全部">全部</option>
          <option value="全部动态">全部动态</option>
          <option value="直播">直播</option>
          <option value="视频">视频</option>
          <option value="音乐">音乐</option>
          <option value="专栏">专栏</option>
        </select>
      </label>
      <fieldset className="grid gap-2 rounded-lg border border-slate-200 p-3">
        <legend className="px-1 text-sm font-medium text-slate-700">目标群聊</legend>
        {targets.length > 0 ? targets.map((target) => (
          <label key={target} className="inline-flex items-center gap-2 text-sm text-slate-700">
            <input type="checkbox" name="targetGroups" value={target} defaultChecked={selectedGroups.has(target)} />
            <span>{target}</span>
          </label>
        )) : <p className="text-sm text-slate-500">暂无可选群聊</p>}
      </fieldset>
      <FormButtons submitText="保存at全体" onCancel={onCancel} />
    </form>
  )
}

/**
 * 编辑器表单共用保存和取消按钮，保证新增和编辑页面都能返回列表。
 */
function FormButtons({submitText, onCancel}: {submitText: string, onCancel: () => void}) {
  return (
    <div className="flex justify-end gap-3">
      <button type="button" onClick={onCancel} className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700">取消</button>
      <button type="submit" className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">{submitText}</button>
    </div>
  )
}

/**
 * 编辑器空态固定高度，切换面板时不会造成弹窗明显跳动。
 */
function EditorEmptyState({text}: {text: string}) {
  return <div className="rounded-lg border border-dashed border-slate-300 p-6 text-sm text-slate-500">{text}</div>
}

/**
 * 配置列表以 JSON 兜底展示未知字段，后续细化不会影响基础可见性。
 */
function EditorList({items, kind, emptyText, onEdit, onDelete}: {
  items: Record<string, unknown>[]
  kind: EditorConfigKind
  emptyText: string
  onEdit: (draft: Record<string, unknown>) => void
  onDelete: (draft: Record<string, unknown>) => void
}) {
  if (items.length === 0) {
    return <EditorEmptyState text={emptyText} />
  }
  return (
    <div className="grid gap-2">
      {items.map((item, index) => {
        const label = readDisplayLabel(item)
        return (
          <div key={readItemField(item, 'key') || `${kind}-${index}`} className="flex min-w-0 flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 p-3 text-sm text-slate-700">
            <span className="min-w-0 break-words">{label}</span>
            <div className="flex shrink-0 gap-2">
              <button type="button" onClick={() => onEdit(item)} className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700">编辑</button>
              <button type="button" onClick={() => onDelete(item)} className="rounded-lg border border-rose-200 px-3 py-1.5 text-sm font-medium text-rose-700">删除</button>
            </div>
          </div>
        )
      })}
    </div>
  )
}

/**
 * 编辑器侧边按钮根据当前面板切换颜色，保持可扫描的操作入口。
 */
function actionButtonClass(active: boolean): string {
  return active
    ? 'rounded-lg bg-slate-950 px-3 py-2 text-left text-sm font-semibold text-white'
    : 'rounded-lg border border-slate-300 px-3 py-2 text-left text-sm font-medium text-slate-700 hover:bg-slate-50'
}

/**
 * 嵌套配置类型映射回对应编辑面板，删除或保存后用于刷新当前列表。
 */
function actionForConfigKind(kind: EditorConfigKind): EditorAction {
  if (kind === 'filter') return 'filters'
  if (kind === 'template') return 'templates'
  return 'atall'
}

/**
 * 编辑表单左上角显示稳定短标题，过滤器显示 key 加类型，模板优先显示名称。
 */
function editorFormTitle(kind: Exclude<EditorConfigKind, 'atall'>, draft: Record<string, unknown> | null): string {
  if (!draft) {
    return ''
  }
  if (kind === 'template') {
    return readItemField(draft, 'name') || readItemField(draft, 'key')
  }
  const key = readItemField(draft, 'key')
  const label = filterKindTitle(readItemField(draft, 'kind') || readItemField(draft, 'label'))
  return [key, label].filter(Boolean).join(' ')
}

/**
 * 过滤器标题只保留类型语义，不把正文内容塞进编辑页标题。
 */
function filterKindTitle(value: string): string {
  const text = value.trim()
  if (!text) {
    return ''
  }
  if (text === 'regex' || text === '正则') {
    return '正则过滤'
  }
  if (text === 'type' || text === '动态类型' || text === '类型') {
    return '类型过滤'
  }
  return text.includes('过滤') ? text : `${text}过滤`
}

/**
 * 配置类型转成用户可见的短标签，供成功和失败状态复用。
 */
function configKindLabel(kind: EditorConfigKind): string {
  if (kind === 'filter') return '过滤器'
  if (kind === 'template') return '模板'
  return 'at全体'
}

/**
 * 订阅卡片字段类型不固定，统一转成字符串供 UI 展示和表单默认值使用。
 */
function readItemField(item: Record<string, unknown>, key: string): string {
  return String(item[key] || '')
}

/**
 * 目标群聊等数组字段以字符串数组返回，过滤掉不可展示的空值。
 */
function readItemArray(item: Record<string, unknown>, key: string): string[] {
  const value = item[key]
  return Array.isArray(value) ? value.map(String).filter(Boolean) : []
}

/**
 * 列表显示优先使用后端摘要，再回退到常见字段和 JSON。
 */
function readDisplayLabel(item: Record<string, unknown>): string {
  return readItemField(item, 'summary')
    || readItemField(item, 'name')
    || readItemField(item, 'content')
    || readItemField(item, 'type')
    || readItemField(item, 'label')
    || readItemField(item, 'key')
    || JSON.stringify(item)
}

/**
 * 删除接口必须拿到稳定 key，缺失 key 时回退到后端聚合字段。
 */
function readConfigKey(item: Record<string, unknown>): string {
  return readItemField(item, 'key') || readItemField(item, 'type') || readItemField(item, 'name') || readItemField(item, 'summary')
}

/**
 * 主题色允许留空恢复默认，非空值必须是标准 #RRGGBB。
 */
function isValidThemeColor(value: string): boolean {
  const text = value.trim()
  return text.length === 0 || /^#[0-9A-Fa-f]{6}$/.test(text)
}

/**
 * 订阅编辑只接受后端主键或兼容 itemId，uid 这类展示字段不参与写入请求。
 */
function readStableSubscriptionId(item: SubscriptionItem): string {
  return readItemField(item, 'id') || readItemField(item, 'itemId')
}
