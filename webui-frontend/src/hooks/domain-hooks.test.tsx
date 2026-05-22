import { renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmationProvider } from '../contexts/ConfirmationContext'
import { useLogs } from './useLogs'
import { useRuntimeSummary } from './useRuntimeSummary'
import { useSettingsFiles } from './useSettingsFiles'
import { useSubscriptions } from './useSubscriptions'
import { useThemePreference } from './useThemePreference'

const createJsonResponse = (status: number, payload: unknown) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => payload,
})

const renderWithConfirmationProvider = (callback: Parameters<typeof renderHook>[0]) => {
  return renderHook(callback, {
    wrapper: ({children}) => <ConfirmationProvider>{children}</ConfirmationProvider>,
  })
}

describe('webui domain hooks', () => {
  it('useRuntimeSummary should call the runtime summary endpoint', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(createJsonResponse(200, {appVersion: '1.0.0'}))

    renderHook(() => useRuntimeSummary({fetchImpl, pollIntervalMs: 100_000}))

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1))
    expect(fetchImpl).toHaveBeenCalledWith('/api/runtime/summary', expect.objectContaining({
      headers: expect.objectContaining({
        Accept: 'application/json',
      }),
    }))
  })

  it('useSettingsFiles should keep snapshot tokens and proxyUpdateMode when saving BiliConfig', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/config/bili-config') && (!init || init.method === 'GET')) {
        return Promise.resolve(createJsonResponse(200, {
          sourceFile: 'BiliConfig.yml',
          snapshotToken: 'bili-snapshot',
          fields: [],
        }))
      }
      if (url.includes('/api/config/bot') && (!init || init.method === 'GET')) {
        return Promise.resolve(createJsonResponse(200, {
          sourceFile: 'bot.yml',
          snapshotToken: 'bot-snapshot',
          fields: [],
        }))
      }
      if (url.includes('/api/config/bili-config') && init?.method === 'POST') {
        return Promise.resolve(createJsonResponse(200, {snapshotToken: 'next-token', success: true}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const user = userEvent.setup()
    const {result} = renderWithConfirmationProvider(() => useSettingsFiles({fetchImpl})) as {
      result: {current: ReturnType<typeof useSettingsFiles>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/config/bot', expect.any(Object)))
    const savePromise = result.current.saveBili({
      snapshotToken: 'bili-snapshot',
      proxyText: 'http://proxy.example:8080',
      currentProxies: ['http://old.example:8080'],
    })

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('密码确认')
    await user.type(screen.getByLabelText('确认密码'), 'secret-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await savePromise

    const postCall = fetchImpl.mock.calls.find(([url, init]) => String(url).includes('/api/config/bili-config') && init?.method === 'POST')
    expect(postCall).toBeTruthy()
    expect(JSON.parse(String(postCall?.[1]?.body))).toMatchObject({
      snapshotToken: 'bili-snapshot',
      confirmationPassword: 'secret-password',
      proxyUpdateMode: 'replace',
    })
  })

  it('useSubscriptions should include confirmationPassword when creating a subscription', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/subscriptions') && (!init || init.method === 'GET')) {
        return Promise.resolve(createJsonResponse(200, {items: []}))
      }
      if (url.endsWith('/api/subscriptions') && init?.method === 'POST') {
        return Promise.resolve(createJsonResponse(200, {success: true}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const user = userEvent.setup()
    const {result} = renderWithConfirmationProvider(() => useSubscriptions({fetchImpl})) as {
      result: {current: ReturnType<typeof useSubscriptions>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/subscriptions', expect.any(Object)))
    const createPromise = result.current.saveSubscription({
      type: 'dynamic',
      uid: '123',
      targetGroup: '456',
    })

    await user.type(await screen.findByLabelText('确认密码'), 'create-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await createPromise

    const postCall = fetchImpl.mock.calls.find(([url, init]) => String(url).endsWith('/api/subscriptions') && init?.method === 'POST')
    expect(JSON.parse(String(postCall?.[1]?.body))).toMatchObject({
      type: 'dynamic',
      uid: '123',
      targetGroup: '456',
      confirmationPassword: 'create-password',
    })
  })

  it('useLogs should go through centered and high-risk confirmation when clearing logs', async () => {
    const fetchImpl = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/logs/sources')) {
        return Promise.resolve(createJsonResponse(200, {sources: [{id: 'source-1'}]}))
      }
      if (url.includes('/api/logs/source-1?tail=500')) {
        return Promise.resolve(createJsonResponse(200, {text: 'line-1'}))
      }
      if (url.endsWith('/api/logs/source-1/clear') && init?.method === 'POST') {
        return Promise.resolve(createJsonResponse(200, {success: true}))
      }
      return Promise.resolve(createJsonResponse(200, {}))
    })

    const user = userEvent.setup()
    const {result} = renderWithConfirmationProvider(() => useLogs({fetchImpl})) as {
      result: {current: ReturnType<typeof useLogs>}
    }

    await waitFor(() => expect(fetchImpl).toHaveBeenCalledWith('/api/logs/sources', expect.any(Object)))
    const clearPromise = result.current.clearCurrentLog('source-1')

    const centeredDialog = await screen.findByRole('dialog')
    expect(centeredDialog).toHaveTextContent('确认操作')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await screen.findByRole('dialog')
    await user.type(screen.getByLabelText('确认密码'), 'log-password')
    await user.click(screen.getByRole('button', {name: '确认'}))

    await clearPromise

    const postCall = fetchImpl.mock.calls.find(([url, init]) => String(url).includes('/api/logs/source-1/clear') && init?.method === 'POST')
    expect(JSON.parse(String(postCall?.[1]?.body))).toMatchObject({
      sourceId: 'source-1',
      confirmationPassword: 'log-password',
    })
  })

  it('useThemePreference should persist the selected preference locally', () => {
    const {result} = renderHook(() => useThemePreference())

    result.current.setPreference('dark')

    expect(window.localStorage.getItem('dynamic_bot_webui_theme')).toBe('dark')
  })
})
