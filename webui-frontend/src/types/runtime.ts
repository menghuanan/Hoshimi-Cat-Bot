/**
 * 运行态摘要只保留页面会直接读取的顶层字段，后续页面再按需扩展。
 */
export type WebUiRuntimeSummary = {
  appVersion?: string
}
