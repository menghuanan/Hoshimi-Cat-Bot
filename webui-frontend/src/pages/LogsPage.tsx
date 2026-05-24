import { useEffect, useRef } from 'react'
import { PageSection } from '../components/PageSection'
import { useLogs } from '../hooks/useLogs'

/**
 * 日志页提供来源、过滤、搜索、自动刷新、导出和本地清屏入口。
 */
export function LogsPage() {
  const {
    sources,
    sourceId,
    filteredRows,
    levels,
    modules,
    levelFilter,
    moduleFilter,
    keyword,
    autoRefresh,
    setSourceId,
    setLevelFilter,
    setModuleFilter,
    setKeyword,
    setAutoRefresh,
    reloadWindow,
    exportFilteredRows,
    clearCurrentLog,
  } = useLogs()
  const logWindowRef = useRef<HTMLPreElement | null>(null)

  // 日志窗口打开或刷新后直接跳到底部，默认展示最新日志而不是最旧日志。
  useEffect(() => {
    const element = logWindowRef.current
    if (!element) {
      return
    }
    element.scrollTop = element.scrollHeight
  }, [filteredRows])

  return (
    <div data-page="logs" className="space-y-6">
      <PageSection title="实时日志">
        <div className="grid min-h-0 gap-4">
          {/* 筛选控件统一撑满各自网格单元，避免桌面宽屏下输入框边界挤到后面的按钮列。 */}
          <div className="grid w-full min-w-0 items-end gap-x-6 gap-y-3 md:grid-cols-4 xl:grid-cols-[repeat(4,minmax(0,1fr))_auto_auto_auto_auto]">
            <label className="grid min-w-0 gap-1 text-xs font-medium text-slate-600">
              日志来源
              <select
                aria-label="日志来源"
                value={sourceId}
                onChange={(event) => {
                  setSourceId(event.target.value)
                  void reloadWindow(event.target.value)
                }}
                className="w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950"
              >
                {sources.map((source) => {
                  const id = readSourceId(source)
                  return <option key={id} value={id}>{id}</option>
                })}
              </select>
            </label>
            <label className="grid min-w-0 gap-1 text-xs font-medium text-slate-600">
              级别
              <select aria-label="级别" value={levelFilter} onChange={(event) => setLevelFilter(event.target.value)} className="w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950">
                <option value="all">全部</option>
                {levels.map((level) => <option key={level} value={level}>{level}</option>)}
              </select>
            </label>
            <label className="grid min-w-0 gap-1 text-xs font-medium text-slate-600">
              模块
              <select aria-label="模块" value={moduleFilter} onChange={(event) => setModuleFilter(event.target.value)} className="w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950">
                <option value="all">全部</option>
                {modules.map((moduleName) => <option key={moduleName} value={moduleName}>{moduleName}</option>)}
              </select>
            </label>
            <label className="grid min-w-0 gap-1 text-xs font-medium text-slate-600">
              搜索
              <input aria-label="搜索" value={keyword} onChange={(event) => setKeyword(event.target.value)} className="w-full min-w-0 rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950" />
            </label>
            <label className="flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700">
              <input aria-label="自动刷新" type="checkbox" checked={autoRefresh} onChange={(event) => setAutoRefresh(event.target.checked)} />
              自动刷新
            </label>
            <button type="button" onClick={() => void reloadWindow()} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 whitespace-nowrap">刷新</button>
            <button type="button" onClick={() => exportFilteredRows()} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700 whitespace-nowrap">导出</button>
            <button type="button" onClick={() => void clearCurrentLog()} className="rounded-lg border border-rose-200 px-3 py-2 text-sm font-medium text-rose-700 hover:bg-rose-50 whitespace-nowrap">清空</button>
          </div>
          <pre ref={logWindowRef} className="h-[calc(100vh-16rem)] min-h-80 overflow-auto rounded-lg border border-slate-200 bg-slate-950 p-4 text-xs leading-6 text-slate-100 shadow-sm">
            {filteredRows.length > 0 ? filteredRows.map((row, index) => (
              <span key={`${row.raw}-${index}`} className="block">{renderLogRow(row.raw, row.level)}</span>
            )) : '暂无日志'}
          </pre>
        </div>
      </PageSection>
    </div>
  )
}

/**
 * 日志来源对象只需要 id 字段，缺失时回退为空字符串避免 select 崩溃。
 */
function readSourceId(source: unknown): string {
  if (!source || typeof source !== 'object' || !('id' in source)) {
    return ''
  }
  return String((source as {id?: unknown}).id || '')
}

/**
 * 日志级别仅高亮级别文本，保留原始行其余内容以便用户复制排查。
 */
function renderLogRow(raw: string, level: string) {
  if (!level || level === 'PLAIN') {
    return raw
  }
  const levelIndex = raw.indexOf(level)
  if (levelIndex < 0) {
    return <span className={logLevelClass(level)}>{raw}</span>
  }
  return (
    <>
      {raw.slice(0, levelIndex)}
      <span className={logLevelClass(level)}>{level}</span>
      {raw.slice(levelIndex + level.length)}
    </>
  )
}

/**
 * INFO/WARN/ERROR 使用明确警示色，其余级别保持低调的可读颜色。
 */
function logLevelClass(level: string): string {
  const classes: Record<string, string> = {
    INFO: 'font-semibold text-emerald-300',
    WARN: 'font-semibold text-amber-300',
    ERROR: 'font-semibold text-rose-300',
  }
  return classes[level] || 'font-semibold text-sky-300'
}
