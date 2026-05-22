import { describe, expect, it } from 'vitest'
import { settingsCategories } from './settingsSchema'
import { buildSettingsSavePayload } from './settingsPayload'

describe('settings payload helpers', () => {
  /**
   * 配置页分区 ID 固定为旧 WebUI 的业务结构，React 渲染和保存都复用同一份元数据。
   */
  it('tracks the eight settings category ids', () => {
    expect(settingsCategories.map((category) => category.id)).toEqual([
      'integration',
      'feature',
      'bili',
      'polling',
      'render',
      'message',
      'admin',
      'translate',
    ])
  })

  /**
   * 代理字段是写入专用字段，空输入必须保留现有代理，显式输入才替换。
   */
  it('maps proxyConfig.proxy to write-only preserve and replace payloads', () => {
    const preserved = buildSettingsSavePayload({
      file: 'biliConfig',
      snapshotToken: 'bili-token',
      confirmationPassword: 'pw-1',
      values: {'proxyConfig.proxy': ''},
    })

    expect(preserved).toMatchObject({
      snapshotToken: 'bili-token',
      confirmationPassword: 'pw-1',
      proxyUpdateMode: 'preserve',
      proxies: [],
    })

    const replaced = buildSettingsSavePayload({
      file: 'biliConfig',
      snapshotToken: 'bili-token',
      confirmationPassword: 'pw-2',
      values: {'proxyConfig.proxy': 'http://a.example\nhttp://b.example'},
    })

    expect(replaced).toMatchObject({
      proxyUpdateMode: 'replace',
      proxies: ['http://a.example', 'http://b.example'],
    })

    const cleared = buildSettingsSavePayload({
      file: 'biliConfig',
      snapshotToken: 'bili-token',
      confirmationPassword: 'pw-3',
      proxyUpdateMode: 'clear',
      values: {'proxyConfig.proxy': ''},
    })

    expect(cleared).toMatchObject({
      proxyUpdateMode: 'clear',
      proxies: [],
    })
  })
})
