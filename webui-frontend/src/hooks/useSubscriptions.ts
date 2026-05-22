import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createSubscription,
  deleteSubscription,
  deleteSubscriptionAtAll,
  deleteSubscriptionFilter,
  deleteSubscriptionTemplate,
  listSubscriptionAtAll,
  listSubscriptionFilters,
  listSubscriptions,
  listSubscriptionTemplates,
  readSubscriptionTheme,
  saveSubscriptionAtAll,
  saveSubscriptionFilter,
  saveSubscriptionTemplate,
  saveSubscriptionTheme,
  setSubscriptionTemplateRandom,
} from '../api/subscriptions'
import type { WebUiJsonRequestOptions } from '../api/http'
import { useHighRiskConfirmation } from './useHighRiskConfirmation'

type UseSubscriptionsOptions = WebUiJsonRequestOptions
type SubscriptionFilterDraft = {
  key: string
  kind: string
  mode: string
  content: string
}
type SubscriptionTemplateDraft = {
  key: string
  type: string
  name: string
  content: string
}
type SubscriptionAtAllDraft = {
  type: string
  targetGroups: string[]
}

/**
 * 订阅页把加载和高风险写操作封成一个 hook，页面只拿结果和命令。
 */
export function useSubscriptions(options: UseSubscriptionsOptions = {}) {
  const {fetchImpl, storage, redirectToLogin} = options
  const requestOptions = useMemo<WebUiJsonRequestOptions>(() => ({
    fetchImpl,
    storage,
    redirectToLogin,
  }), [fetchImpl, redirectToLogin, storage])
  const {requestHighRiskConfirmation} = useHighRiskConfirmation()
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
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认新增订阅')
    if (!confirmationPassword) {
      return null
    }
    return createSubscription({...payload, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  const removeSubscription = useCallback(async (itemId: string) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认删除订阅')
    if (!confirmationPassword) {
      return null
    }
    return deleteSubscription(itemId, confirmationPassword, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

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
   * 保存过滤器前统一获取 WebUI 密码，调用方不能绕过高风险确认。
   */
  const saveFilter = useCallback(async (itemId: string, draft: SubscriptionFilterDraft) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认保存过滤器')
    if (!confirmationPassword) {
      return null
    }
    return saveSubscriptionFilter(itemId, {...draft, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  /**
   * 保存模板时只让页面提供业务字段，确认密码由 hook 注入。
   */
  const saveTemplate = useCallback(async (itemId: string, draft: SubscriptionTemplateDraft) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认保存模板')
    if (!confirmationPassword) {
      return null
    }
    return saveSubscriptionTemplate(itemId, {...draft, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  /**
   * 保存 @全体配置时保持多目标列表原样传给后端。
   */
  const saveAtAll = useCallback(async (itemId: string, draft: SubscriptionAtAllDraft) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认保存at全体')
    if (!confirmationPassword) {
      return null
    }
    return saveSubscriptionAtAll(itemId, {...draft, confirmationPassword}, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  /**
   * 保存主题色只暴露颜色参数，避免页面层组装确认 payload。
   */
  const saveTheme = useCallback(async (itemId: string, color: string) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认保存主题色')
    if (!confirmationPassword) {
      return null
    }
    return saveSubscriptionTheme(itemId, color, confirmationPassword, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  /**
   * 配置项删除按编辑器类型分发到对应端点，确认密码仍走同一弹窗。
   */
  const removeConfig = useCallback(async (itemId: string, kind: 'filter' | 'template' | 'atall', key: string) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认删除配置项')
    if (!confirmationPassword) {
      return null
    }
    if (kind === 'filter') {
      return deleteSubscriptionFilter(itemId, key, confirmationPassword, requestOptions)
    }
    if (kind === 'template') {
      return deleteSubscriptionTemplate(itemId, key, confirmationPassword, requestOptions)
    }
    return deleteSubscriptionAtAll(itemId, key, confirmationPassword, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

  /**
   * 随机模板开关是即时写入，hook 负责在切换前拿到确认密码。
   */
  const toggleRandomTemplate = useCallback(async (itemId: string, enabled: boolean) => {
    const confirmationPassword = await requestHighRiskConfirmation('请输入 WebUI 密码确认切换随机模板')
    if (!confirmationPassword) {
      return null
    }
    return setSubscriptionTemplateRandom(itemId, enabled, confirmationPassword, requestOptions)
  }, [requestHighRiskConfirmation, requestOptions])

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
    saveFilter,
    saveTemplate,
    saveAtAll,
    saveTheme,
    removeConfig,
    toggleRandomTemplate,
  }
}
