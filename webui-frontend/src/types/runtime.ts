/**
 * B 站账号摘要只保留展示登录状态需要的字段，避免前端接触 Cookie。
 */
export type WebUiBiliAccountStatus = {
  loggedIn?: boolean
  uid?: number | null
  cookieConfigured?: boolean
}

/**
 * 平台连接摘要聚合连接状态和 transport 观测值，页面不感知具体实现。
 */
export type WebUiWebSocketStatus = {
  connected?: boolean
  reconnectAttempts?: number
  activeSessionCount?: number
  transports?: string[]
  note?: string | null
}

/**
 * 今日推送统计与后端 DTO 对齐，首页只读取计数和最后成功时间。
 */
export type WebUiTodayPushStats = {
  date?: string
  total?: number
  dynamic?: number
  live?: number
  liveClose?: number
  failed?: number
  lastSuccessAtEpochMillis?: number | null
}

/**
 * 最近推送记录保持扁平结构，便于首页列表直接渲染。
 */
export type WebUiRecentPushRecord = {
  timestampEpochMillis?: number
  type?: string
  typeLabel?: string
  success?: boolean
  statusLabel?: string
  summary?: string
  target?: string | null
}

/**
 * 资源使用率统一表达内存和存储，百分比缺失时页面使用占位。
 */
export type WebUiResourceUsage = {
  usedBytes?: number
  totalBytes?: number
  usagePercent?: number | null
}

/**
 * 宿主运行态包含旧 WebUI 首页展示的系统指标。
 */
export type WebUiHostRuntimeStatus = {
  startedAtEpochMillis?: number
  systemTimeEpochMillis?: number
  systemLoadAverage?: number | null
  cpuUsagePercent?: number | null
  memory?: WebUiResourceUsage
  storage?: WebUiResourceUsage
}

/**
 * 首页状态卡左侧强调色只暴露允许的设计系统色名，避免页面散落任意 Tailwind 类。
 */
export type WebUiDashboardStatusTone = 'emerald' | 'sky' | 'amber' | 'rose'

/**
 * 运行态摘要映射后端 `/api/runtime/summary` DTO，字段保持可选以兼容旧响应。
 */
export type WebUiRuntimeSummary = {
  lifecycleState?: string
  uptimeSeconds?: number
  appVersion?: string
  platformAdapterInitialized?: boolean
  platformReady?: boolean
  webUiEnabled?: boolean
  restartRequestMode?: string
  subscriptionCount?: number
  dynamicSubscriptionCount?: number
  bangumiSubscriptionCount?: number
  groupCount?: number
  account?: WebUiBiliAccountStatus
  webSocket?: WebUiWebSocketStatus
  todayPushStats?: WebUiTodayPushStats
  recentPushRecords?: WebUiRecentPushRecord[]
  host?: WebUiHostRuntimeStatus
}

/**
 * 首页视图模型收敛页面需要的关键指标，减少组件重复拆解 DTO。
 */
export type WebUiDashboardRuntimeFields = {
  appVersion: string
  lifecycleState: string
  uptimeSeconds: number | null
  startedAtEpochMillis: number | null
  systemTimeEpochMillis: number | null
  systemLoadAverage: number | null
  cpuUsagePercent: number | null
  memoryUsagePercent: number | null
  storageUsagePercent: number | null
  storageUsedBytes: number | null
  storageTotalBytes: number | null
  accountLoggedIn: boolean | null
  accountUid: number | null
  lifecycleTone: WebUiDashboardStatusTone
  accountTone: WebUiDashboardStatusTone
  platformReady: boolean | null
  webSocketConnected: boolean | null
  webSocketTone: WebUiDashboardStatusTone
  todayPushTotal: number | null
  recentPushRecordsCount: number
  recentPushRecords: WebUiDashboardRecentPushRecord[]
}

/**
 * 首页最近推送记录只保留展示所需字段，不继续透出后端原始 target 或内部类型值。
 */
export type WebUiDashboardRecentPushRecord = {
  timestampEpochMillis: number | null
  typeLabel: string
  statusLabel: string
  subscriptionInfo: string
}
