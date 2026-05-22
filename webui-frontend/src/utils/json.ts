/**
 * 请求体统一通过 JSON 序列化出口，避免各个 API 模块手写 stringify 逻辑。
 */
export function toJsonBody(value: unknown): string {
  return JSON.stringify(value)
}

/**
 * 响应 JSON 解析失败时返回空对象，调用方再决定是否按业务错误处理。
 */
export async function readJsonResponse<T>(response: Pick<Response, 'json'>): Promise<T | Record<string, never>> {
  try {
    return await response.json() as T
  } catch {
    return {}
  }
}
