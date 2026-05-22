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
 * 首页展示运行态摘要和常用入口，数据缺失时保持稳定占位。
 */
export function DashboardPage() {
  const {summary, dashboard, loading} = useRuntimeSummary({pollIntervalMs: 60_000})
  const recentPushRecords = summary?.recentPushRecords || []

  return (
    <div data-page="home" className="space-y-6">
      <PageSection title="运行概览" description="实时摘要由 /api/runtime/summary 提供，页面每分钟自动刷新。">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <StatusCard label="版本" value={loading ? '--' : dashboard.appVersion} tone="emerald" detail="当前运行版本" />
          <StatusCard label="运行状态" value={loading ? '同步中' : dashboard.lifecycleState} tone="sky" detail="Bot 运行状态" />
          <StatusCard label="B站账号信息" value={formatState(dashboard.accountLoggedIn, '已登录', '未登录')} tone="rose" detail={dashboard.accountUid ? `UID ${dashboard.accountUid}` : 'UID --'} />
          <StatusCard label="WebSocket 状态" value={formatState(dashboard.webSocketConnected, '已连接', '未连接')} tone="sky" detail={`会话：${summary?.webSocket?.activeSessionCount ?? '--'}`} />
          <StatusCard label="今日推送统计" value={formatCount(dashboard.todayPushTotal, ' 条')} tone="amber" detail={`动态：${summary?.todayPushStats?.dynamic ?? '--'}　直播：${summary?.todayPushStats?.live ?? '--'}`} />
          <StatusCard label="最近推送" value={formatCount(dashboard.recentPushRecordsCount, ' 条')} tone="emerald" detail="保留最近推送摘要" />
          <StatusCard label="配置入口" value="系统配置" tone="amber" detail="保存时需要密码确认" />
          <StatusCard label="日志窗口" value="可轮询" tone="rose" detail="清空日志需要双重确认" />
        </div>
      </PageSection>

      <PageSection title="最近推送记录">
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          {recentPushRecords.length === 0 ? (
            <div className="px-4 py-5 text-sm text-slate-500">暂无最近推送记录</div>
          ) : (
            <div className="divide-y divide-slate-100">
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
  )
}
