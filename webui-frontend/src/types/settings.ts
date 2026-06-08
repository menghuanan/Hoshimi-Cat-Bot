/**
 * 配置保存响应只需要 snapshotToken 和业务层是否成功的最小形态。
 */
export type WebUiSettingsSaveResult = {
  snapshotToken?: string
  success?: boolean
  persisted?: boolean
  effectiveLevel?: string
  recommendedAction?: string
  message?: string
  conflictDetected?: boolean
  validationErrors?: string[]
}

/**
 * 后端热重载 job 阶段只用于驱动保存按钮和结果提示，前端不自行推断重启需求。
 */
export type WebUiConfigHotReloadPhase = 'QUEUED' | 'SAVING' | 'APPLYING' | 'APPLIED' | 'FAILED'

export type WebUiConfigFileKind = 'BILI_CONFIG' | 'BILI_DATA' | 'BOT_CONFIG'

export type WebUiConfigHotReloadJob = {
  jobId: string
  phase: WebUiConfigHotReloadPhase
  files?: WebUiConfigFileKind[]
  outcomes?: Array<{file: WebUiConfigFileKind, result: WebUiSettingsSaveResult}>
  coalescedSignals?: number
  webUiRedirectUrl?: string | null
  message?: string
}
