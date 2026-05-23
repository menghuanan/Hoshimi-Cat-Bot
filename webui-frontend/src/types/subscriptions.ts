/**
 * 订阅写请求复用最小的确认密码字段，其他业务字段由具体表单按需补充。
 */
export type WebUiSubscriptionWritePayload = {
  confirmationPassword: string
}
