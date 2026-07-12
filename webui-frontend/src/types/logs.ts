/**
 * 解析后的日志行保留原文，同时拆出过滤控件需要的级别和模块。
 */
export type WebUiParsedLogRow = {
  raw: string
  level: string
  module: string
  message: string
}
