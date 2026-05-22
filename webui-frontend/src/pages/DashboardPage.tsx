import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { useRuntimeSummary } from '../hooks/useRuntimeSummary'

/**
 * 二态运行值缺失时展示稳定占位，避免首页在旧响应上抛错。
 */
function formatState(value: boolean | null, enabledText: string, disabledText: string) {
  if (value === null) return '--'
  return value ? enabledText : disabledText
}

/**
 * 计数字段统一追加单位，缺失时保持旧 WebUI 的占位语义。
 */
function formatCount(value: number | null, unit = '') {
  if (value === null) return '--'
  return `${value}${unit}`
}

/**
 * 时间戳按本地时间展示，避免前端引入额外时区配置。
 */
function formatEpochMillis(value: number | null) {
  if (value === null) return '--'
  return new Date(value).toLocaleString('zh-CN', {hour12: false})
}

/**
 * 运行时长以天、小时、分钟压缩展示，保证卡片内文本不会过长。
 */
function formatDuration(seconds: number | null) {
  if (seconds === null) return '--'
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分钟`
  return `${minutes} 分钟`
}

/**
 * 容量值按 G 展示具体占用，避免存储卡片只出现抽象百分比。
 */
function formatStorageUsage(usedBytes: number | null, totalBytes: number | null) {
  if (usedBytes === null || totalBytes === null || totalBytes <= 0) return '--'
  const toGib = (value: number) => Math.round(value / 1024 ** 3)
  return `${toGib(usedBytes)}G/${toGib(totalBytes)}G`
}

/**
 * 运行信息行保持左右两列，便于和旧 WebUI 的扫描密度对齐。
 */
function MetricRow({label, value}: {label: string, value: string}) {
  return (
    <div className="grid grid-cols-[7rem_minmax(0,1fr)] gap-3 border-b border-slate-100 px-4 py-3 last:border-b-0">
      <span className="text-sm text-slate-500">{label}</span>
      <strong className="min-w-0 break-words text-sm font-semibold text-slate-950">{value}</strong>
    </div>
  )
}

/**
 * 资源条用稳定文本和固定高度进度展示，避免指标刷新时改变布局尺寸。
 */
function ResourceMeter({label, value, detail}: {label: string, value: number | null, detail?: string}) {
  const width = Math.max(0, Math.min(100, value ?? 0))
  const display = detail || (value === null ? '--' : `${Number.isInteger(value) ? value : value.toFixed(1)}%`)
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="font-medium text-slate-700">{label}</span>
        <strong className="text-slate-950">{display}</strong>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-slate-100">
        <span className="block h-full rounded-full bg-emerald-500" style={{width: `${width}%`}} />
      </div>
    </div>
  )
}

/**
 * 首页展示运行态摘要和常用入口，数据缺失时保持稳定占位。
 */
export function DashboardPage() {
  const {summary, dashboard, loading} = useRuntimeSummary({pollIntervalMs: 60_000})
  const recentPushRecords = summary?.recentPushRecords || []

  return (
    <div data-page="home" className="space-y-6">
      <PageSection
        title="运行概览"
      >
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
          <StatusCard label="版本" value={loading ? '--' : dashboard.appVersion} tone="emerald" detail="当前运行版本" />
          <StatusCard label="运行状态" value={loading ? '同步中' : dashboard.lifecycleState} tone="sky" detail="Bot 运行状态" />
          <StatusCard label="B站账号信息" value={formatState(dashboard.accountLoggedIn, '已登录', '未登录')} tone="rose" detail={dashboard.accountUid ? `UID ${dashboard.accountUid}` : 'UID --'} />
          <StatusCard label="WebSocket 状态" value={formatState(dashboard.webSocketConnected, '已连接', '未连接')} tone="sky" detail={`会话：${summary?.webSocket?.activeSessionCount ?? '--'}`} />
          <StatusCard label="今日推送" value={formatCount(dashboard.todayPushTotal, ' 条')} tone="amber" detail={`动态：${summary?.todayPushStats?.dynamic ?? '--'} / 直播：${summary?.todayPushStats?.live ?? '--'}`} />
        </div>
      </PageSection>

      <div className="grid items-stretch gap-6 xl:grid-cols-2">
        <PageSection title="运行信息">
          <div className="grid h-full gap-4 lg:grid-cols-2">
            <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
              <MetricRow label="启动时间" value={formatEpochMillis(dashboard.startedAtEpochMillis)} />
              <MetricRow label="运行时长" value={formatDuration(dashboard.uptimeSeconds)} />
              <MetricRow label="系统时间" value={formatEpochMillis(dashboard.systemTimeEpochMillis)} />
              <MetricRow label="系统负载" value={dashboard.systemLoadAverage === null ? '--' : String(dashboard.systemLoadAverage)} />
            </div>
            <div className="space-y-5 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <ResourceMeter label="CPU" value={dashboard.cpuUsagePercent} />
              <ResourceMeter label="内存" value={dashboard.memoryUsagePercent} />
              <ResourceMeter label="存储" value={dashboard.storageUsagePercent} detail={formatStorageUsage(dashboard.storageUsedBytes, dashboard.storageTotalBytes)} />
            </div>
          </div>
        </PageSection>

        <PageSection title="最近推送记录">
          <div className="h-full max-h-[22rem] overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
            {recentPushRecords.length === 0 ? (
              <div className="px-4 py-5 text-sm text-slate-500">暂无最近推送记录</div>
            ) : (
              <div className="max-h-[22rem] divide-y divide-slate-100 overflow-y-auto">
                {recentPushRecords.map((record, index) => (
                  <div key={`${record.timestampEpochMillis || index}-${record.summary || index}`} className="grid gap-1 px-4 py-3 text-sm md:grid-cols-[8rem_1fr_8rem] md:items-center">
                    <span className="font-medium text-slate-700">{record.typeLabel || record.type || '--'}</span>
                    <span className="min-w-0 break-words text-slate-950">{record.summary || '--'}</span>
                    <span className={record.success ? 'text-emerald-600' : 'text-rose-700'}>{record.statusLabel || '--'}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </PageSection>
      </div>
    </div>
  )
}
