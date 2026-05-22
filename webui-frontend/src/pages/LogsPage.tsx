import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { useLogs } from '../hooks/useLogs'

/**
 * 日志页提供来源、过滤、搜索、自动刷新、导出和清空入口，清空动作仍走双重确认。
 */
export function LogsPage() {
  const {
    loading,
    sources,
    sourceId,
    rows,
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

  return (
    <div data-page="logs" className="space-y-6">
      <PageSection title="日志" description="日志窗口读取 /api/logs/{sourceId}，清空当前来源前会连续确认。">
        <div className="grid gap-4 md:grid-cols-3">
          <StatusCard label="来源数量" value={loading ? '--' : sources.length} tone="emerald" />
          <StatusCard label="当前来源" value={sourceId || '--'} tone="sky" />
          <StatusCard label="日志行数" value={rows.length} tone="amber" detail={`过滤后 ${filteredRows.length} 行`} />
        </div>
      </PageSection>

      <PageSection
        title="日志窗口"
        actions={(
          <>
            <label className="grid gap-1 text-xs font-medium text-slate-600">
              日志来源
              <select
                aria-label="日志来源"
                value={sourceId}
                onChange={(event) => {
                  setSourceId(event.target.value)
                  void reloadWindow(event.target.value)
                }}
                className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950"
              >
                {sources.map((source) => {
                  const id = readSourceId(source)
                  return <option key={id} value={id}>{id}</option>
                })}
              </select>
            </label>
            <label className="grid gap-1 text-xs font-medium text-slate-600">
              级别
              <select aria-label="级别" value={levelFilter} onChange={(event) => setLevelFilter(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950">
                <option value="all">全部</option>
                {levels.map((level) => <option key={level} value={level}>{level}</option>)}
              </select>
            </label>
            <label className="grid gap-1 text-xs font-medium text-slate-600">
              模块
              <select aria-label="模块" value={moduleFilter} onChange={(event) => setModuleFilter(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950">
                <option value="all">全部</option>
                {modules.map((moduleName) => <option key={moduleName} value={moduleName}>{moduleName}</option>)}
              </select>
            </label>
            <label className="grid gap-1 text-xs font-medium text-slate-600">
              关键词
              <input aria-label="关键词" value={keyword} onChange={(event) => setKeyword(event.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950" />
            </label>
            <label className="flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700">
              <input aria-label="自动刷新" type="checkbox" checked={autoRefresh} onChange={(event) => setAutoRefresh(event.target.checked)} />
              自动刷新
            </label>
            <button type="button" onClick={() => void reloadWindow()} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700">刷新</button>
            <button type="button" onClick={() => exportFilteredRows()} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-medium text-slate-700">导出</button>
            <button type="button" onClick={() => void clearCurrentLog()} className="rounded-lg border border-rose-200 px-3 py-2 text-sm font-medium text-rose-700 hover:bg-rose-50">清空</button>
          </>
        )}
      >
        <pre className="min-h-96 overflow-auto rounded-lg border border-slate-200 bg-slate-950 p-4 text-xs leading-6 text-slate-100 shadow-sm">
          {filteredRows.length > 0 ? filteredRows.map((row) => row.raw).join('\n') : '暂无日志'}
        </pre>
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
