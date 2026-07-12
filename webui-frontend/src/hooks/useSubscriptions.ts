import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createSubscription,
  buildSubscriptionCreatePayload,
  deleteSubscription,
  deleteSubscriptionAtAll,
  deleteSubscriptionFilter,
  deleteSubscriptionTarget,
  deleteSubscriptionTemplate,
  deleteSubscriptionUid,
  listSubscriptionAtAll,
  listSubscriptionFilters,
  listSubscriptions,
  listSubscriptionTemplates,
  listSubscriptionTargets,
  listSubscriptionUids,
  readSubscriptionTheme,
  saveSubscriptionAtAll,
  saveSubscriptionFilter,
  saveSubscriptionTarget,
  saveSubscriptionTemplate,
  saveSubscriptionTheme,
  saveSubscriptionUid,
  setSubscriptionTemplateRandom,
} from '../api/subscriptions'
import type { WebUiJsonRequestOptions } from '../api/http'
import { normalizeVisibleMessage } from '../utils/errorMessages'

type UseSubscriptionsOptions = WebUiJsonRequestOptions
type SubscriptionFilterDraft = {
  key: string
  kind: string
  mode: string
  content: string
  targetGroups?: string[]
}
type SubscriptionTemplateDraft = {
  key: string
  type: string
  name: string
  content: string
  targetGroups?: string[]
}
type SubscriptionAtAllDraft = {
  type: string
  targetGroups: string[]
}
type SubscriptionThemeDraft = {
  color: string
  targetGroups: string[]
}
type SubscriptionTargetDraft = {
  targetGroup: string
}
type SubscriptionUidDraft = {
  uid: string
}

/**
 * 写接口可能以 HTTP 200 返回业务失败，前端必须把具体 message 暴露给页面状态。
 */
function ensureSubscriptionWriteSucceeded(result: unknown, fallbackMessage: string): unknown {
  if (result && typeof result === 'object' && 'success' in result && (result as {success?: unknown}).success === false) {
    const message = normalizeVisibleMessage((result as {message?: unknown}).message || '', fallbackMessage)
    throw new Error(message || fallbackMessage)
  }
  return result
}

/**
 * 订阅页把加载和写操作封成一个 hook，页面只拿结果和命令。
 */
export function useSubscriptions(options: UseSubscriptionsOptions = {}) {
  const {fetchImpl, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin])
  const [items, setItems] = useState<unknown[]>([])
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    setLoading(true)
    const payload = await listSubscriptions(requestOptions) as {items?: unknown[]}
    setItems(Array.isArray(payload?.items) ? payload.items : [])
    setLoading(false)
  }, [requestOptions])

  useEffect(() => {
    // 初始订阅加载异步排队，避免 effect 同步触发 setState 链。
    const timer = window.setTimeout(() => {
      void reload()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [reload])

  const saveSubscription = useCallback(async (payload: Record<string, unknown>) => {
    // 新增表单使用分类型显示字段，提交前统一转换成后端订阅写入 DTO。
    const result = await createSubscription(buildSubscriptionCreatePayload({
      type: String(payload.type || 'dynamic'),
      uid: String(payload.uid || ''),
      targetGroup: String(payload.targetGroup || ''),
      bangumiId: String(payload.bangumiId || ''),
      bangumiTarget: String(payload.bangumiTarget || ''),
      groupName: String(payload.groupName || ''),
      groupUid: String(payload.groupUid || ''),
      groupTarget: String(payload.groupTarget || ''),
    }), requestOptions)
    return ensureSubscriptionWriteSucceeded(result, '新增订阅失败')
  }, [requestOptions])

  const removeSubscription = useCallback(async (itemId: string) => {
    return ensureSubscriptionWriteSucceeded(await deleteSubscription(itemId, requestOptions), '删除订阅失败')
  }, [requestOptions])

  /**
   * 嵌套过滤器列表保持只读加载，页面拿到原始 DTO 后再决定如何渲染。
   */
  const loadFilters = useCallback((itemId: string) => {
    return listSubscriptionFilters(itemId, requestOptions)
  }, [requestOptions])

  /**
   * 模板编辑器需要同时读取模板列表和随机模板开关状态。
   */
  const loadTemplates = useCallback((itemId: string) => {
    return listSubscriptionTemplates(itemId, requestOptions)
  }, [requestOptions])

  /**
   * @全体编辑器读取按类型聚合后的配置项，避免页面解析底层 BiliData。
   */
  const loadAtAll = useCallback((itemId: string) => {
    return listSubscriptionAtAll(itemId, requestOptions)
  }, [requestOptions])

  /**
   * 主题色读取保持单独方法，便于编辑器打开时按需加载。
   */
  const loadTheme = useCallback((itemId: string) => {
    return readSubscriptionTheme(itemId, requestOptions)
  }, [requestOptions])

  /**
   * 推送群聊列表作为通用嵌套编辑器读取，三类订阅都可使用。
   */
  const loadTargets = useCallback((itemId: string) => {
    return listSubscriptionTargets(itemId, requestOptions)
  }, [requestOptions])

  /**
   * 分组订阅 ID 列表只在分组卡片展示，hook 保持薄封装。
   */
  const loadUids = useCallback((itemId: string) => {
    return listSubscriptionUids(itemId, requestOptions)
  }, [requestOptions])

  /**
   * 保存过滤器只提交编辑器业务字段，认证由统一请求层处理。
   */
  const saveFilter = useCallback(async (itemId: string, draft: SubscriptionFilterDraft) => {
    return ensureSubscriptionWriteSucceeded(await saveSubscriptionFilter(itemId, draft, requestOptions), '保存过滤器失败')
  }, [requestOptions])

  /**
   * 保存模板时只让页面提供业务字段。
   */
  const saveTemplate = useCallback(async (itemId: string, draft: SubscriptionTemplateDraft) => {
    return ensureSubscriptionWriteSucceeded(await saveSubscriptionTemplate(itemId, draft, requestOptions), '保存模板失败')
  }, [requestOptions])

  /**
   * 保存 @全体配置时保持多目标列表原样传给后端。
   */
  const saveAtAll = useCallback(async (itemId: string, draft: SubscriptionAtAllDraft) => {
    return ensureSubscriptionWriteSucceeded(await saveSubscriptionAtAll(itemId, draft, requestOptions), '保存at全体失败')
  }, [requestOptions])

  /**
   * 保存主题色时页面只负责提交颜色和可选目标群聊。
   */
  const saveTheme = useCallback(async (itemId: string, draft: SubscriptionThemeDraft) => {
    return ensureSubscriptionWriteSucceeded(await saveSubscriptionTheme(itemId, draft.color, draft.targetGroups, requestOptions), '保存主题色失败')
  }, [requestOptions])

  /**
   * 保存推送群聊时页面只负责正整数输入校验。
   */
  const saveTarget = useCallback(async (itemId: string, draft: SubscriptionTargetDraft) => {
    return ensureSubscriptionWriteSucceeded(await saveSubscriptionTarget(itemId, draft, requestOptions), '保存推送群聊失败')
  }, [requestOptions])

  /**
   * 保存分组订阅 ID 后由后端默认绑定全部推送群聊。
   */
  const saveUid = useCallback(async (itemId: string, draft: SubscriptionUidDraft) => {
    return ensureSubscriptionWriteSucceeded(await saveSubscriptionUid(itemId, draft, requestOptions), '保存订阅ID失败')
  }, [requestOptions])

  /**
   * 配置项删除按编辑器类型分发到对应端点。
   */
  const removeConfig = useCallback(async (itemId: string, kind: 'filter' | 'template' | 'atall', key: string) => {
    if (kind === 'filter') {
      return ensureSubscriptionWriteSucceeded(await deleteSubscriptionFilter(itemId, key, requestOptions), '删除过滤器失败')
    }
    if (kind === 'template') {
      return ensureSubscriptionWriteSucceeded(await deleteSubscriptionTemplate(itemId, key, requestOptions), '删除模板失败')
    }
    return ensureSubscriptionWriteSucceeded(await deleteSubscriptionAtAll(itemId, key, requestOptions), '删除at全体失败')
  }, [requestOptions])

  /**
   * 删除推送群聊是高风险写操作，后端会按订阅类型清理关联配置。
   */
  const removeTarget = useCallback(async (itemId: string, key: string) => {
    return ensureSubscriptionWriteSucceeded(await deleteSubscriptionTarget(itemId, key, requestOptions), '删除推送群聊失败')
  }, [requestOptions])

  /**
   * 删除分组订阅 ID 必须经过确认，后端会走对应取消订阅链路。
   */
  const removeUid = useCallback(async (itemId: string, key: string) => {
    return ensureSubscriptionWriteSucceeded(await deleteSubscriptionUid(itemId, key, requestOptions), '删除订阅ID失败')
  }, [requestOptions])

  /**
   * 随机模板开关是即时写入，hook 只负责提交业务状态。
   */
  const toggleRandomTemplate = useCallback(async (itemId: string, enabled: boolean) => {
    return ensureSubscriptionWriteSucceeded(await setSubscriptionTemplateRandom(itemId, enabled, requestOptions), '切换随机模板失败')
  }, [requestOptions])

  return {
    items,
    loading,
    reload,
    saveSubscription,
    removeSubscription,
    loadFilters,
    loadTemplates,
    loadAtAll,
    loadTheme,
    loadTargets,
    loadUids,
    saveFilter,
    saveTemplate,
    saveAtAll,
    saveTheme,
    saveTarget,
    saveUid,
    removeConfig,
    removeTarget,
    removeUid,
    toggleRandomTemplate,
  }
}
