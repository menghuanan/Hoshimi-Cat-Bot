import { useState, type FormEvent } from 'react'

type EditorAction = 'overview' | 'filters' | 'templates' | 'atall' | 'theme'
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
  const [activeAction, setActiveAction] = useState<EditorAction>('overview')
  const [filters, setFilters] = useState<Record<string, unknown>[]>([])
  const [templates, setTemplates] = useState<Record<string, unknown>[]>([])
  const [randomEnabled, setRandomEnabled] = useState(false)
  const [atAllItems, setAtAllItems] = useState<Record<string, unknown>[]>([])
  const [themeColor, setThemeColor] = useState('')
  const [formMode, setFormMode] = useState<'none' | 'filter' | 'template' | 'atall'>('none')
  const [status, setStatus] = useState('')

  if (!item) {
    return null
  }

  const itemId = readItemField(item, 'id') || readItemField(item, 'itemId') || readItemField(item, 'uid')
  const targets = readItemArray(item, 'targets')

  /**
   * 配置面板切换时按需加载对应后端数据，并保留操作按钮常驻。
   */
  const openAction = async (nextAction: EditorAction) => {
    setStatus('')
    setFormMode('none')
    setActiveAction(nextAction)
    if (!itemId || nextAction === 'overview') {
      return
    }
    if (nextAction === 'filters') {
      const payload = await actions.loadFilters(itemId) as {filters?: Record<string, unknown>[]}
      setFilters(Array.isArray(payload?.filters) ? payload.filters : [])
    }
    if (nextAction === 'templates') {
      const payload = await actions.loadTemplates(itemId) as {templates?: Record<string, unknown>[], randomEnabled?: boolean}
      setTemplates(Array.isArray(payload?.templates) ? payload.templates : [])
      setRandomEnabled(Boolean(payload?.randomEnabled))
    }
    if (nextAction === 'atall') {
      const payload = await actions.loadAtAll(itemId) as {items?: Record<string, unknown>[]}
      setAtAllItems(Array.isArray(payload?.items) ? payload.items : [])
    }
    if (nextAction === 'theme') {
      const payload = await actions.loadTheme(itemId) as {color?: string}
      setThemeColor(String(payload?.color || ''))
    }
  }

  /**
   * 随机模板切换立即写入，失败时交给状态文案提示并保持当前 UI 状态。
   */
  const toggleRandom = async (enabled: boolean) => {
    if (!itemId) {
      return
    }
    await actions.toggleRandomTemplate(itemId, enabled)
    setRandomEnabled(enabled)
    setStatus(enabled ? '随机模板已开启' : '随机模板已关闭')
    await onReload()
  }

  /**
   * 保存过滤器后刷新当前面板，确保列表和订阅卡片都同步更新。
   */
  const submitFilter = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      return
    }
    const form = new FormData(event.currentTarget)
    await actions.saveFilter(itemId, {
      key: '',
      kind: String(form.get('kind') || 'regex'),
      mode: String(form.get('mode') || 'black'),
      content: String(form.get('content') || '').trim(),
    })
    setStatus('过滤器已保存')
    await openAction('filters')
    await onReload()
  }

  /**
   * 保存模板时保留模板正文原样，支持用户主动保存空正文模板。
   */
  const submitTemplate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      return
    }
    const form = new FormData(event.currentTarget)
    await actions.saveTemplate(itemId, {
      key: '',
      type: String(form.get('type') || 'dynamic'),
      name: String(form.get('name') || '').trim(),
      content: String(form.get('content') || ''),
    })
    setStatus('模板已保存')
    await openAction('templates')
    await onReload()
  }

  /**
   * @全体保存读取多选群聊，保持与旧 WebUI 的多目标编辑能力一致。
   */
  const submitAtAll = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      return
    }
    const form = new FormData(event.currentTarget)
    const targetGroups = form.getAll('targetGroups').map(String)
    await actions.saveAtAll(itemId, {
      type: String(form.get('type') || '全部'),
      targetGroups,
    })
    setStatus('@全体已保存')
    await openAction('atall')
    await onReload()
  }

  /**
   * 主题色保存使用当前输入值，HEX 细节仍由后端二次校验。
   */
  const submitTheme = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!itemId) {
      return
    }
    await actions.saveTheme(itemId, themeColor.trim())
    setStatus('主题色已保存')
    await onReload()
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-slate-950/50 px-4 py-6" role="presentation" onMouseDown={onClose}>
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="subscription-editor-title"
        className="grid max-h-[90vh] w-full max-w-5xl gap-4 overflow-y-auto rounded-lg border border-slate-200 bg-white p-5 shadow-2xl lg:grid-cols-[13rem_1fr]"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <aside className="space-y-3">
          <div>
            <h3 id="subscription-editor-title" className="text-base font-semibold text-slate-950">编辑订阅配置</h3>
            <p className="mt-1 text-sm text-slate-600">{readItemField(item, 'title') || '未命名订阅'}</p>
          </div>
          <div className="grid gap-2">
            <button type="button" onClick={() => void openAction('filters')} className={actionButtonClass(activeAction === 'filters')}>编辑过滤器</button>
            <button type="button" onClick={() => void openAction('templates')} className={actionButtonClass(activeAction === 'templates')}>编辑模板</button>
            <button type="button" onClick={() => void openAction('atall')} className={actionButtonClass(activeAction === 'atall')}>编辑at全体</button>
            <button type="button" onClick={() => void openAction('theme')} className={actionButtonClass(activeAction === 'theme')}>编辑主题色</button>
          </div>
          <button type="button" onClick={onClose} className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700">关闭</button>
        </aside>

        <div className="min-w-0 space-y-4">
          {activeAction === 'overview' ? <EditorEmptyState text="选择左侧编辑器开始配置" /> : null}
          {activeAction === 'filters' ? (
            <div className="space-y-3">
              <EditorList items={filters} emptyText="暂无过滤器" />
              {formMode === 'filter' ? <FilterForm onSubmit={submitFilter} /> : <button type="button" onClick={() => setFormMode('filter')} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">添加过滤器</button>}
            </div>
          ) : null}
          {activeAction === 'templates' ? (
            <div className="space-y-3">
              <label className="inline-flex items-center gap-2 text-sm font-medium text-slate-700">
                <input type="checkbox" checked={randomEnabled} onChange={(event) => void toggleRandom(event.target.checked)} />
                <span>随机模板</span>
              </label>
              <EditorList items={templates} emptyText="暂无模板" />
              {formMode === 'template' ? <TemplateForm onSubmit={submitTemplate} /> : <button type="button" onClick={() => setFormMode('template')} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">添加模板</button>}
            </div>
          ) : null}
          {activeAction === 'atall' ? (
            <div className="space-y-3">
              <EditorList items={atAllItems} emptyText="暂无atall信息" />
              {formMode === 'atall' ? <AtAllForm targets={targets} onSubmit={submitAtAll} /> : <button type="button" onClick={() => setFormMode('atall')} className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">添加at全体</button>}
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
          {status ? <p className="text-sm font-medium text-slate-700">{status}</p> : null}
        </div>
      </section>
    </div>
  )
}

/**
 * 过滤器表单保留旧 WebUI 的类型、模式和内容三组核心字段。
 */
function FilterForm({onSubmit}: {onSubmit: (event: FormEvent<HTMLFormElement>) => void}) {
  return (
    <form className="grid gap-3 rounded-lg border border-slate-200 p-4" onSubmit={onSubmit}>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>过滤类型</span>
        <select name="kind" className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
          <option value="regex">正则</option>
          <option value="type">动态类型</option>
        </select>
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>规则模式</span>
        <select name="mode" className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
          <option value="black">黑名单</option>
          <option value="white">白名单</option>
        </select>
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>规则内容</span>
        <input name="content" className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
      </label>
      <button type="submit" className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">保存过滤器</button>
    </form>
  )
}

/**
 * 模板表单保持类型、名称和正文，正文不做前端重写。
 */
function TemplateForm({onSubmit}: {onSubmit: (event: FormEvent<HTMLFormElement>) => void}) {
  return (
    <form className="grid gap-3 rounded-lg border border-slate-200 p-4" onSubmit={onSubmit}>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>模板类型</span>
        <select name="type" className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
          <option value="dynamic">动态</option>
          <option value="live">直播</option>
          <option value="liveClose">下播</option>
        </select>
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>模板名称</span>
        <input name="name" className="rounded-lg border border-slate-300 px-3 py-2 text-sm" />
      </label>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>模板内容</span>
        <textarea name="content" className="min-h-28 rounded-lg border border-slate-300 px-3 py-2 text-sm" />
      </label>
      <button type="submit" className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">保存模板</button>
    </form>
  )
}

/**
 * @全体表单按订阅目标生成多选项，支持一次选择多个目标群。
 */
function AtAllForm({targets, onSubmit}: {targets: string[], onSubmit: (event: FormEvent<HTMLFormElement>) => void}) {
  return (
    <form className="grid gap-3 rounded-lg border border-slate-200 p-4" onSubmit={onSubmit}>
      <label className="grid gap-1 text-sm font-medium text-slate-700">
        <span>at类型</span>
        <select name="type" className="rounded-lg border border-slate-300 px-3 py-2 text-sm">
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
            <input type="checkbox" name="targetGroups" value={target} />
            <span>{target}</span>
          </label>
        )) : <p className="text-sm text-slate-500">暂无可选群聊</p>}
      </fieldset>
      <button type="submit" className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white">保存at全体</button>
    </form>
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
function EditorList({items, emptyText}: {items: Record<string, unknown>[], emptyText: string}) {
  if (items.length === 0) {
    return <EditorEmptyState text={emptyText} />
  }
  return (
    <div className="grid gap-2">
      {items.map((item, index) => (
        <div key={readItemField(item, 'key') || index} className="rounded-lg border border-slate-200 p-3 text-sm text-slate-700">
          {readItemField(item, 'summary') || readItemField(item, 'name') || readItemField(item, 'content') || JSON.stringify(item)}
        </div>
      ))}
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
