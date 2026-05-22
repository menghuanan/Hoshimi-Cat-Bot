import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { useRuntimeSummary } from '../hooks/useRuntimeSummary'

/**
 * 首页展示运行态摘要和常用入口，数据缺失时保持稳定占位。
 */
export function DashboardPage() {
  const {summary, loading} = useRuntimeSummary({pollIntervalMs: 60_000})

  return (
    <div data-page="home" className="space-y-6">
      <PageSection title="运行概览" description="实时摘要由 /api/runtime/summary 提供，页面每分钟自动刷新。">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <StatusCard label="版本" value={loading ? '--' : summary?.appVersion || '--'} tone="emerald" detail="当前运行版本" />
          <StatusCard label="运行状态" value={loading ? '同步中' : '在线'} tone="sky" detail="WebUI API 可访问" />
          <StatusCard label="配置入口" value="系统配置" tone="amber" detail="保存时需要密码确认" />
          <StatusCard label="日志窗口" value="可轮询" tone="rose" detail="清空日志需要双重确认" />
        </div>
      </PageSection>
    </div>
  )
}
