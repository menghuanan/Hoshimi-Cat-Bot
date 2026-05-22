import { describe, expect, it, vi } from 'vitest'
import { buildAuthHeaders, requestJson } from './http'
import { applyAuthSession, applyLoginResult, loginWithPassword, restoreSession } from './auth'
import { buildBiliConfigSavePayload, buildBotConfigSavePayload } from './settings'
import {
  buildSubscriptionCreatePayload,
  buildSubscriptionDeletePayload,
  listSubscriptionFilters,
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

const createStorage = (token = '') => {
  const values = new Map<string, string>()
  if (token) {
    values.set('webuiToken', token)
  }
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value)
    },
    removeItem: (key: string) => {
      values.delete(key)
    },
  }
}

describe('webui api contracts', () => {
  it('buildAuthHeaders should keep bearer and JSON negotiation headers', () => {
    const storage = createStorage('token-123')

    expect(buildAuthHeaders(false, storage)).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer token-123',
    })
    expect(buildAuthHeaders(true, storage)).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer token-123',
      'Content-Type': 'application/json',
    })
  })

  it('requestJson should redirect unauthorized requests through the shared handler', async () => {
    const storage = createStorage('token-123')
    const redirectToLogin = vi.fn()
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(401, {message: 'nope'}))

    await expect(requestJson('/api/runtime/summary', {
      storage,
      fetchImpl,
      redirectToLogin,
    })).rejects.toThrow('HTTP 401')

    expect(storage.getItem('webuiToken')).toBeNull()
    expect(redirectToLogin).toHaveBeenCalledTimes(1)
  })

  it('loginWithPassword should post the password without auth headers', async () => {
    const storage = createStorage()
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {token: 'abc', mustChangePassword: false}))

    const result = await loginWithPassword('secret', {storage, fetchImpl})

    expect(fetchImpl).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        Accept: 'application/json',
        'Content-Type': 'application/json',
      }),
      body: JSON.stringify({password: 'secret'}),
    }))
    expect(result.token).toBe('abc')
    expect(storage.getItem('webuiToken')).toBe('abc')
  })

  it('restoreSession should send the bearer token and preserve authenticated state', async () => {
    const storage = createStorage('stored-token')
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {authenticated: true, mustChangePassword: false}))

    const result = await restoreSession({storage, fetchImpl})

    expect(fetchImpl).toHaveBeenCalledWith('/api/auth/session', expect.objectContaining({
      headers: expect.objectContaining({
        Accept: 'application/json',
        Authorization: 'Bearer stored-token',
      }),
    }))
    expect(result.authenticated).toBe(true)
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

  it('applyLoginResult should route must-change-password users to the change-password screen', () => {
    const goShell = vi.fn()
    const goChangePassword = vi.fn()

    const outcome = applyLoginResult({token: 'abc', mustChangePassword: true}, {
      onShell: goShell,
      onChangePassword: goChangePassword,
    })

    expect(outcome).toBe('change-password')
    expect(goChangePassword).toHaveBeenCalledTimes(1)
    expect(goShell).not.toHaveBeenCalled()
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
    const storage = createStorage('subscription-token')
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {success: true}))

    await listSubscriptionFilters('item/1', {storage, fetchImpl})
    await saveSubscriptionFilter('item/1', {
      key: 'filter-1',
      kind: 'regex',
      mode: 'black',
      content: '广告',
      confirmationPassword: 'pw-filter',
    }, {storage, fetchImpl})
    await setSubscriptionTemplateRandom('item/1', true, 'pw-random', {storage, fetchImpl})

    expect(fetchImpl).toHaveBeenNthCalledWith(1, '/api/subscriptions/item%2F1/filters', expect.objectContaining({
      method: 'GET',
      body: undefined,
    }))
    expect(fetchImpl).toHaveBeenNthCalledWith(2, '/api/subscriptions/item%2F1/filters', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        Authorization: 'Bearer subscription-token',
        'Content-Type': 'application/json',
      }),
      body: JSON.stringify({
        key: 'filter-1',
        kind: 'regex',
        mode: 'black',
        content: '广告',
        confirmationPassword: 'pw-filter',
      }),
    }))
    expect(fetchImpl).toHaveBeenNthCalledWith(3, '/api/subscriptions/item%2F1/templates/random', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        enabled: true,
        confirmationPassword: 'pw-random',
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

    await fetchRuntimeSummary({fetchImpl, storage: createStorage('runtime-token')})

    expect(fetchImpl).toHaveBeenCalledWith('/api/runtime/summary', expect.objectContaining({
      headers: expect.objectContaining({
        Authorization: 'Bearer runtime-token',
      }),
    }))
  })
})
