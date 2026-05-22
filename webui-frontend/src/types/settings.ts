/**
 * 配置保存响应只需要 snapshotToken 和业务层是否成功的最小形态。
 */
export type WebUiSettingsSaveResult = {
  snapshotToken?: string
  success?: boolean
  message?: string
  conflictDetected?: boolean
  validationErrors?: string[]
}
