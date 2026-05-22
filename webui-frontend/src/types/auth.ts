/**
 * 登录响应最小保留 token 和是否强制改密两个字段，和当前 WebUI 认证流对齐。
 */
export type WebUiAuthResponse = {
  token?: string
  mustChangePassword?: boolean
  authenticated?: boolean
  message?: string
}
