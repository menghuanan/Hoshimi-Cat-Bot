/**
 * 日志清空请求保持 sourceId + confirmationPassword 的最小写入形态。
 */
export type WebUiLogClearPayload = {
  sourceId: string
  confirmationPassword: string
}
