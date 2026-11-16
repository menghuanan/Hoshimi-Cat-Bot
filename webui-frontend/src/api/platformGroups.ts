import { requestJson } from './http'

export type PlatformGroupOption = {
  subject: string
  openId: string
  name: string
  alias: string
  joined: boolean
  acceptsProactive: boolean | null
  observedAt: number
  refreshedAt: number
}

export type PlatformGroupDirectory = {
  enabled: boolean
  generation: string
  connected: boolean
  nameStatus: string
  stateStatus: string
  storageError: string | null
  items: PlatformGroupOption[]
}

/** 群目录只读当前平台快照，认证和错误处理沿用统一请求层。 */
export function listPlatformGroups(): Promise<PlatformGroupDirectory> {
  return requestJson('/api/platform/groups', {method: 'GET', includeJson: false})
}

/** 刷新只调度已知群的信息查询；代际阻止旧页面操作新账号。 */
export function refreshPlatformGroups(generation: string): Promise<unknown> {
  return requestJson('/api/platform/groups/refresh', {method: 'POST', body: {generation}})
}

/** 用户备注与官方群名分开保存，留空表示恢复官方名称或未知名称。 */
export function savePlatformGroupAlias(generation: string, openId: string, alias: string): Promise<unknown> {
  return requestJson('/api/platform/groups/alias', {method: 'POST', body: {generation, openId, alias}})
}
