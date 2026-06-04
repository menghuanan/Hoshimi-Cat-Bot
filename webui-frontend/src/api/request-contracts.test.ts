import { describe, expect, it, vi } from 'vitest'
import { requestJson } from './http'
import { applyAuthSession, applyLoginResult, loginWithPassword, restoreSession } from './auth'
import { buildBiliConfigSavePayload, buildBotConfigSavePayload } from './settings'
import {
  buildSubscriptionCreatePayload,
  buildSubscriptionDeletePayload,
  deleteSubscriptionTarget,
  deleteSubscriptionTemplate,
  listSubscriptionFilters,
  listSubscriptionTargets,
  saveSubscriptionTarget,
  saveSubscriptionFilter,
  setSubscriptionTemplateRandom,
} from './subscriptions'
import { buildLogClearPayload } from './logs'
import { fetchRuntimeSummary } from './runtime'

const createJsonResponse = (status: number, payload: unknown) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => payload,
})

describe('webui api contracts', () => {
  it('requestJson should attach CSRF headers for unsafe requests without bearer auth', async () => {
    document.cookie = 'hoshimi_cat_bot_webui_csrf=csrf-123; path=/'
    const getItemSpy = vi.spyOn(window.sessionStorage, 'getItem')
    const setItemSpy = vi.spyOn(window.sessionStorage, 'setItem')
    const removeItemSpy = vi.spyOn(window.sessionStorage, 'removeItem')
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {ok: true}))

    await requestJson('/api/runtime/summary', {
      method: 'POST',
      body: {value: 'next'},
      fetchImpl,
    })

    expect(fetchImpl).toHaveBeenCalledWith('/api/runtime/summary', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-CSRF-Token': 'csrf-123',
      }),
      body: JSON.stringify({value: 'next'}),
    }))
    expect(getItemSpy).not.toHaveBeenCalled()
    expect(setItemSpy).not.toHaveBeenCalled()
    expect(removeItemSpy).not.toHaveBeenCalled()
    getItemSpy.mockRestore()
    setItemSpy.mockRestore()
    removeItemSpy.mockRestore()
  })

  it('requestJson should redirect unauthorized requests through the shared handler', async () => {
    const redirectToLogin = vi.fn()
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(401, {message: 'nope'}))
    const removeItemSpy = vi.spyOn(window.sessionStorage, 'removeItem')

    await expect(requestJson('/api/runtime/summary', {
      fetchImpl,
      redirectToLogin,
    })).rejects.toThrow('请重新登录')

    expect(removeItemSpy).not.toHaveBeenCalled()
    expect(redirectToLogin).toHaveBeenCalledTimes(1)
    removeItemSpy.mockRestore()
  })

  it('requestJson should hide status codes from generic request failures', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(500, {}))

    await expect(requestJson('/api/runtime/summary', {
      fetchImpl,
    })).rejects.toThrow('请求失败，请稍后重试')
  })

  it('loginWithPassword should post the password without writing a token', async () => {
    document.cookie = 'hoshimi_cat_bot_webui_csrf=csrf-login; path=/'
    const getItemSpy = vi.spyOn(window.sessionStorage, 'getItem')
    const setItemSpy = vi.spyOn(window.sessionStorage, 'setItem')
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {mustChangePassword: false}))

    const result = await loginWithPassword('secret', {fetchImpl})

    expect(fetchImpl).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-CSRF-Token': 'csrf-login',
      }),
      body: JSON.stringify({password: 'secret'}),
    }))
    expect(result.mustChangePassword).toBe(false)
    expect(getItemSpy).not.toHaveBeenCalled()
    expect(setItemSpy).not.toHaveBeenCalled()
    getItemSpy.mockRestore()
    setItemSpy.mockRestore()
  })

  it('restoreSession should use the cookie-backed session probe without bearer headers', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {authenticated: true, mustChangePassword: false}))
    const getItemSpy = vi.spyOn(window.sessionStorage, 'getItem')

    const result = await restoreSession({fetchImpl})

    expect(fetchImpl).toHaveBeenCalledWith('/api/auth/session', expect.objectContaining({
      headers: expect.objectContaining({
        Accept: 'application/json',
      }),
    }))
    expect(result.authenticated).toBe(true)
    expect(getItemSpy).not.toHaveBeenCalled()
    getItemSpy.mockRestore()
  })

  it('applyAuthSession should route authenticated users to the shell', () => {
    const goShell = vi.fn()
    const goLogin = vi.fn()

    const outcome = applyAuthSession({authenticated: true, mustChangePassword: false}, {
      onShell: goShell,
      onLogin: goLogin,
    })

    expect(outcome).toBe('shell')
    expect(goShell).toHaveBeenCalledTimes(1)
    expect(goLogin).not.toHaveBeenCalled()
  })

  it('applyLoginResult should route must-change-password users to the change-password screen without storing tokens', () => {
    const setItemSpy = vi.spyOn(window.sessionStorage, 'setItem')
    const goShell = vi.fn()
    const goChangePassword = vi.fn()

    const outcome = applyLoginResult({mustChangePassword: true}, {
      onShell: goShell,
      onChangePassword: goChangePassword,
    })

    expect(outcome).toBe('change-password')
    expect(goChangePassword).toHaveBeenCalledTimes(1)
    expect(goShell).not.toHaveBeenCalled()
    expect(setItemSpy).not.toHaveBeenCalled()
    setItemSpy.mockRestore()
  })

  it('applyAuthSession should keep expired sessions on the login page without clearing stored tokens', () => {
    const removeItemSpy = vi.spyOn(window.sessionStorage, 'removeItem')
    const goShell = vi.fn()
    const goLogin = vi.fn()

    const outcome = applyAuthSession({authenticated: false, mustChangePassword: false}, {
      onShell: goShell,
      onLogin: goLogin,
    })

    expect(outcome).toBe('login')
    expect(goLogin).toHaveBeenCalledTimes(1)
    expect(goShell).not.toHaveBeenCalled()
    expect(removeItemSpy).not.toHaveBeenCalled()
    removeItemSpy.mockRestore()
  })

  it('buildBiliConfigSavePayload should preserve proxy update mode and confirmation password', () => {
    const payload = buildBiliConfigSavePayload({
      snapshotToken: 'snapshot-token',
      confirmationPassword: 'pw-1',
      proxyText: '',
      currentProxies: ['http://old-proxy'],
    })

    expect(payload.snapshotToken).toBe('snapshot-token')
    expect(payload.confirmationPassword).toBe('pw-1')
    expect(payload.proxyUpdateMode).toBe('preserve')

    const replaced = buildBiliConfigSavePayload({
      snapshotToken: 'snapshot-token',
      confirmationPassword: 'pw-2',
      proxyText: 'http://new-proxy',
      currentProxies: ['http://old-proxy'],
    })

    expect(replaced.proxyUpdateMode).toBe('replace')
    expect(replaced.proxies).toEqual(['http://new-proxy'])

    const cleared = buildBiliConfigSavePayload({
      snapshotToken: 'snapshot-token',
      confirmationPassword: 'pw-3',
      proxyText: '',
      proxyUpdateMode: 'clear',
      currentProxies: ['http://old-proxy'],
    })

    expect(cleared.proxyUpdateMode).toBe('clear')
    expect(cleared.proxies).toEqual([])
  })

  it('buildBotConfigSavePayload should keep confirmation password and snapshot token', () => {
    const payload = buildBotConfigSavePayload({
      snapshotToken: 'bot-snapshot',
      confirmationPassword: 'pw-bot',
      token: 'secret-token',
    })

    expect(payload.snapshotToken).toBe('bot-snapshot')
    expect(payload.confirmationPassword).toBe('pw-bot')
    expect(payload.oneBot11Token).toBe('secret-token')
  })

  it('subscription payload builders should keep confirmation passwords', () => {
    expect(buildSubscriptionCreatePayload({
      type: 'dynamic',
      uid: '123',
      targetGroup: '456',
      confirmationPassword: 'pw-create',
    })).toMatchObject({
      type: 'dynamic',
      uid: '123',
      targetGroup: '456',
      confirmationPassword: 'pw-create',
    })

    expect(buildSubscriptionDeletePayload('item-1', 'pw-delete')).toEqual({
      confirmationPassword: 'pw-delete',
      itemId: 'item-1',
    })
  })

  it('subscription nested config requests should target existing backend routes', async () => {
    document.cookie = 'hoshimi_cat_bot_webui_csrf=csrf-subscription; path=/'
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {success: true}))

    await listSubscriptionFilters('item/1', {fetchImpl})
    await saveSubscriptionFilter('item/1', {
      key: 'filter-1',
      kind: 'regex',
      mode: 'black',
      content: '广告',
      targetGroups: ['onebot11:group:10001'],
      confirmationPassword: 'pw-filter',
    }, {fetchImpl})
    await setSubscriptionTemplateRandom('item/1', true, 'pw-random', {fetchImpl})

    expect(fetchImpl).toHaveBeenNthCalledWith(1, '/api/subscriptions/item%2F1/filters', expect.objectContaining({
      method: 'GET',
      body: undefined,
      headers: expect.objectContaining({
        Accept: 'application/json',
      }),
    }))
    expect(fetchImpl).toHaveBeenNthCalledWith(2, '/api/subscriptions/item%2F1/filters', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        'Content-Type': 'application/json',
        'X-CSRF-Token': 'csrf-subscription',
      }),
      body: JSON.stringify({
        key: 'filter-1',
        kind: 'regex',
        mode: 'black',
        content: '广告',
        targetGroups: ['onebot11:group:10001'],
        confirmationPassword: 'pw-filter',
      }),
    }))
    expect(fetchImpl).toHaveBeenNthCalledWith(3, '/api/subscriptions/item%2F1/templates/random', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        'X-CSRF-Token': 'csrf-subscription',
      }),
      body: JSON.stringify({
        enabled: true,
        confirmationPassword: 'pw-random',
      }),
    }))
  })

  /**
   * 模板删除是高风险确认写接口，403 必须保留为密码错误而不是触发登录重定向。
   */
  it('deleteSubscriptionTemplate should surface 403 as a password error without redirecting', async () => {
    document.cookie = 'hoshimi_cat_bot_webui_csrf=csrf-template-delete; path=/'
    const redirectToLogin = vi.fn()
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(403, {message: 'bad password'}))

    await expect(deleteSubscriptionTemplate('item/1', 'template-1', 'pw-delete', {
      fetchImpl,
      redirectToLogin,
    })).rejects.toThrow('密码错误，请重试')

    expect(redirectToLogin).not.toHaveBeenCalled()
    expect(fetchImpl).toHaveBeenCalledWith('/api/subscriptions/item%2F1/templates/template-1', expect.objectContaining({
      method: 'DELETE',
      headers: expect.objectContaining({
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-CSRF-Token': 'csrf-template-delete',
      }),
      body: JSON.stringify({
        confirmationPassword: 'pw-delete',
      }),
    }))
  })

  /**
   * 推送群聊编辑器复用订阅嵌套路由，新增和删除都必须携带高风险确认密码。
   */
  it('subscription target helpers should use nested target routes with confirmation payloads', async () => {
    document.cookie = 'hoshimi_cat_bot_webui_csrf=csrf-target; path=/'
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {success: true}))

    await listSubscriptionTargets('item/1', {fetchImpl})
    await saveSubscriptionTarget('item/1', {targetGroup: '10001', confirmationPassword: 'pw-target'}, {fetchImpl})
    await deleteSubscriptionTarget('item/1', 'onebot11:group:10001', 'pw-delete-target', {fetchImpl})

    expect(fetchImpl).toHaveBeenNthCalledWith(1, '/api/subscriptions/item%2F1/targets', expect.objectContaining({
      method: 'GET',
      body: undefined,
    }))
    expect(fetchImpl).toHaveBeenNthCalledWith(2, '/api/subscriptions/item%2F1/targets', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        targetGroup: '10001',
        confirmationPassword: 'pw-target',
      }),
    }))
    expect(fetchImpl).toHaveBeenNthCalledWith(3, '/api/subscriptions/item%2F1/targets/onebot11%3Agroup%3A10001', expect.objectContaining({
      method: 'DELETE',
      body: JSON.stringify({
        confirmationPassword: 'pw-delete-target',
      }),
    }))
  })

  it('log clear payload should keep confirmation password', () => {
    expect(buildLogClearPayload('source-1', 'pw-log')).toEqual({
      sourceId: 'source-1',
      confirmationPassword: 'pw-log',
    })
  })

  it('runtime summary helper should call the runtime summary endpoint', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {appVersion: '1.0.0'}))

    await fetchRuntimeSummary({fetchImpl})

    expect(fetchImpl).toHaveBeenCalledWith('/api/runtime/summary', expect.objectContaining({
      headers: expect.objectContaining({
        Accept: 'application/json',
      }),
    }))
  })
})
