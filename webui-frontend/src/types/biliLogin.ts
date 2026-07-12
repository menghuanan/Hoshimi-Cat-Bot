export type WebUiBiliLoginPhase =
  | 'WAITING_FOR_SCAN'
  | 'WAITING_FOR_CONFIRMATION'
  | 'COMMITTING'
  | 'SUCCEEDED'
  | 'EXPIRED'
  | 'FAILED'
  | 'CANCELLED'

/** 浏览器只持有脱敏会话状态，二维码 Base64 仅在创建响应中存在。 */
export type WebUiBiliLoginSession = {
  sessionId: string
  phase: WebUiBiliLoginPhase
  expiresAtEpochMillis: number
  message: string
  qrImageBase64?: string | null
}
