import { useCallback, useEffect, useMemo, useState } from 'react'
import { createSubscription, deleteSubscription, listSubscriptions } from '../api/subscriptions'
import type { WebUiJsonRequestOptions } from '../api/http'
import { useHighRiskConfirmation } from './useHighRiskConfirmation'

type UseSubscriptionsOptions = WebUiJsonRequestOptions

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

  return {items, loading, reload, saveSubscription, removeSubscription}
}
