import { useEffect, useRef, useState } from 'react'
import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { useRuntimeSummary } from '../hooks/useRuntimeSummary'

// 最近推送记录使用同一列宽，保证表头和每条记录在视觉上严格对齐。
const RECENT_PUSH_GRID_CLASS = 'grid-cols-[8.75rem_4rem_1.25rem_2rem_minmax(0,1fr)_10.5rem]'

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
  const date = new Date(value)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${date.getFullYear()}年${month}月${day}日 ${hour}：${minute}`
}

/**
 * 运行信息使用短时间，避免首页多个年份文本干扰最近推送记录的时间识别。
 */
function formatRuntimeTime(value: number | null) {
  if (value === null) return '--'
  return new Date(value).toLocaleString('zh-CN', {month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false})
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
 * 资源数字只在目标值变化时滚动，首屏直接使用快照值避免加载阶段闪烁。
 */
function useCountUpValue(value: number | null) {
  const target = Math.max(0, Math.min(100, value ?? 0))
  const previousTargetRef = useRef(target)
  const [displayValue, setDisplayValue] = useState(target)

  useEffect(() => {
    const previousTarget = previousTargetRef.current
    if (previousTarget === target) {
      setDisplayValue(target)
      return undefined
    }
    const startedAt = window.performance.now()
    const durationMs = 400
    let frameId = 0
    let finished = false
    const finishTimer = window.setTimeout(() => {
      finished = true
      previousTargetRef.current = target
      setDisplayValue(target)
      window.cancelAnimationFrame(frameId)
    }, durationMs + 30)
    const step = (now: number) => {
      if (finished) {
        return
      }
      const progress = Math.min(1, (now - startedAt) / durationMs)
      const eased = 1 - (1 - progress) ** 3
      setDisplayValue(previousTarget + (target - previousTarget) * eased)
      if (progress < 1) {
        frameId = window.requestAnimationFrame(step)
      } else {
        window.clearTimeout(finishTimer)
        previousTargetRef.current = target
      }
    }
    frameId = window.requestAnimationFrame(step)
    return () => {
      window.clearTimeout(finishTimer)
      window.cancelAnimationFrame(frameId)
    }
  }, [target])

  return displayValue
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
  const animatedValue = useCountUpValue(value)
  const display = detail || (value === null ? '--' : `${animatedValue.toFixed(1)}%`)
  const toneClass = resourceMeterToneClass(width)
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="font-medium text-slate-700">{label}</span>
        <strong data-count-up-value={value === null ? undefined : String(width)} className="text-slate-950">{display}</strong>
      </div>
      <progress className={`resource-meter ${toneClass}`} max={100} value={width} aria-label={label} />
    </div>
  )
}

/**
 * 资源阈值颜色保持和运维直觉一致：正常绿色，接近风险黄色，高风险红色。
 */
function resourceMeterToneClass(value: number): string {
  if (value > 85) return 'resource-meter-danger'
  if (value >= 60) return 'resource-meter-warn'
  return 'resource-meter-safe'
}

/**
 * 最近推送记录单元格只展示值本身，时间列可独立靠右以贴近卡片边缘。
 */
function PushRecordCell({value, align = 'left', valueClassName = ''}: {value: string, align?: 'left' | 'right', valueClassName?: string}) {
  const alignmentClass = align === 'right' ? 'text-right' : 'text-left'
  return (
    <strong data-testid="recent-push-cell" className={`block min-w-0 truncate whitespace-nowrap ${alignmentClass} text-sm font-semibold ${valueClassName || 'text-slate-950'}`.trim()} title={value}>{value}</strong>
  )
}

/**
 * 首页展示运行态摘要和常用入口，数据缺失时保持稳定占位。
 */
export function DashboardPage() {
  const {summary, dashboard, loading} = useRuntimeSummary({pollIntervalMs: 60_000})
  const recentPushRecords = dashboard.recentPushRecords
  // 首页卡片只保留最近 7 条，避免列表过长时压缩卡片内的列宽和运行信息。
  const visibleRecentPushRecords = recentPushRecords.slice(0, 7)

  return (
    <div data-page="home" className="space-y-6">
      <PageSection
        title="运行概览"
      >
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
          <StatusCard label="版本" value={loading ? '--' : dashboard.appVersion} tone="emerald" detail="当前运行版本" />
          <StatusCard label="运行状态" value={loading ? '同步中' : dashboard.lifecycleState} tone={loading ? 'sky' : dashboard.lifecycleTone} detail="Bot 运行状态" />
          <StatusCard label="B站账号信息" value={formatState(dashboard.accountLoggedIn, '已登录', '未登录')} tone={dashboard.accountTone} detail={dashboard.accountUid ? `UID ${dashboard.accountUid}` : 'UID --'} />
          <StatusCard label="WebSocket 状态" value={formatState(dashboard.webSocketConnected, '已连接', '未连接')} tone={dashboard.webSocketTone} detail={`会话：${summary?.webSocket?.activeSessionCount ?? '--'} / 重连：${summary?.webSocket?.reconnectAttempts ?? '--'}`} />
          <StatusCard label="今日推送" value={formatCount(dashboard.todayPushTotal, ' 条')} tone="amber" detail={`动态：${summary?.todayPushStats?.dynamic ?? '--'} / 直播：${summary?.todayPushStats?.live ?? '--'}`} />
        </div>
      </PageSection>

      <div className="grid items-stretch gap-6 xl:grid-cols-2">
        <PageSection title="运行信息">
          <div className="grid h-full gap-4 lg:grid-cols-2">
            <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
              <MetricRow label="启动时间" value={formatRuntimeTime(dashboard.startedAtEpochMillis)} />
              <MetricRow label="运行时长" value={formatDuration(dashboard.uptimeSeconds)} />
              <MetricRow label="系统时间" value={formatRuntimeTime(dashboard.systemTimeEpochMillis)} />
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
          <div className="flex h-full min-h-[16rem] max-h-[22rem] flex-col overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
            {/* 固定表头独立于记录滚动区，确保空记录和有记录时列语义一致。 */}
            <div data-testid="recent-push-header" className={`grid ${RECENT_PUSH_GRID_CLASS} gap-1 border-b border-slate-200 bg-slate-50 px-2 py-3 text-xs font-semibold text-slate-600`}>
              <span className="min-w-0 truncate whitespace-nowrap">订阅名称</span>
              <span className="min-w-0 truncate whitespace-nowrap">推送类型</span>
              {/* 类型与状态之间保留固定空列，剩余空间放到状态之后，避免宽屏下状态被推得过远。 */}
              <span aria-hidden="true" />
              <span className="min-w-0 truncate whitespace-nowrap">状态</span>
              <span aria-hidden="true" />
              <span className="min-w-0 truncate whitespace-nowrap text-left">时间</span>
            </div>
            {visibleRecentPushRecords.length === 0 ? (
              <div className="grid flex-1 place-items-center px-2 py-5 text-sm text-slate-500">暂无最近推送记录</div>
            ) : (
              <div className="min-h-0 flex-1 divide-y divide-slate-100 overflow-y-auto">
                {visibleRecentPushRecords.map((record, index) => (
                  <div data-testid={`recent-push-row-${index}`} key={`${record.timestampEpochMillis || index}-${record.subscriptionInfo || index}`} className="px-2 py-2 text-sm">
                    <div className={`grid ${RECENT_PUSH_GRID_CLASS} gap-1`}>
                      <PushRecordCell value={record.subscriptionInfo} />
                      <PushRecordCell value={record.typeLabel} />
                      <span aria-hidden="true" />
                      <PushRecordCell
                        value={record.statusLabel}
                        valueClassName={record.statusLabel === '成功' || record.statusLabel === '已发送' ? 'text-emerald-600' : 'text-rose-700'}
                      />
                      <span aria-hidden="true" />
                      <PushRecordCell
                        value={formatEpochMillis(record.timestampEpochMillis ?? null)}
                        align="right"
                        valueClassName="text-slate-500"
                      />
                    </div>
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
