/**
 * 登录响应只保留页面流程需要的会话状态字段，不再依赖前端 token 回传。
 */
export type WebUiAuthResponse = {
  mustChangePassword?: boolean
  authenticated?: boolean
  message?: string
}
