export type SubscriptionEditorKind = 'targets' | 'uids' | 'filters' | 'templates' | 'atall' | 'theme'

/**
 * 订阅配置编辑器的路由片段集中管理，避免页面和 API helper 拼出不一致的路径。
 */
export const subscriptionEditorRoutes: Record<SubscriptionEditorKind, string> = {
  targets: 'targets',
  uids: 'uids',
  filters: 'filters',
  templates: 'templates',
  atall: 'atall',
  theme: 'theme',
}

/**
 * 嵌套订阅配置 URL 统一编码订阅 ID 和配置 key，保持与 Ktor 路由契约一致。
 */
export function buildSubscriptionEditorUrl(subscriptionId: string, kind: SubscriptionEditorKind, key?: string): string {
  const baseUrl = `/api/subscriptions/${encodeURIComponent(subscriptionId)}/${subscriptionEditorRoutes[kind]}`
  return key ? `${baseUrl}/${encodeURIComponent(key)}` : baseUrl
}

/**
 * 随机模板开关是模板编辑器下的独立写入端点，单独暴露以减少调用方字符串拼接。
 */
export function buildSubscriptionTemplateRandomUrl(subscriptionId: string): string {
  return `${buildSubscriptionEditorUrl(subscriptionId, 'templates')}/random`
}
