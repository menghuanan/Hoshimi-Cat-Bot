import { describe, expect, it } from 'vitest'
import { settingsCategories, validateSettingsValues } from './settingsSchema'
import { settingsFieldDescriptions } from './settingsFieldDescriptions'
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
   * 每个可见配置项都必须有底部说明，避免页面只剩下字段名和输入框。
   */
  it('provides a bottom description for every settings field', () => {
    const missingDescriptions = settingsCategories
      .flatMap((category) => category.fields.map((field) => field.key))
      .filter((key) => !settingsFieldDescriptions[key]?.trim())

    expect(missingDescriptions).toEqual([])
  })

  /**
   * WebUI 主机描述必须明确提醒 0.0.0.0 会把管理界面对外暴露。
   */
  it('warns that webui.host = 0.0.0.0 exposes the WebUI externally', () => {
    expect(settingsFieldDescriptions['webui.host']).toContain('0.0.0.0')
    expect(settingsFieldDescriptions['webui.host']).toContain('对外')
  })

  /**
   * 八个设置分区必须覆盖旧 WebUI 已暴露的可编辑项，避免 React schema 漏字段。
   */
  it('covers the editable field keys required by the old WebUI settings panels', () => {
    const keys = settingsCategories.flatMap((category) => category.fields.map((field) => field.key))

    expect(keys).toEqual(expect.arrayContaining([
      'platform.type',
      'platform.adapter',
      'platform.onebot11.host',
      'platform.onebot11.port',
      'platform.onebot11.token',
      'platform.onebot11.useTls',
      'platform.onebot11.heartbeatInterval',
      'platform.onebot11.reconnectInterval',
      'platform.onebot11.sendMode',
      'platform.onebot11.maxReconnectAttempts',
      'platform.onebot11.connectTimeout',
      'platform.qqOfficial.appId',
      'platform.qqOfficial.appSecret',
      'platform.qqOfficial.botToken',
      'webui.enabled',
      'webui.host',
      'webui.port',
      'webui.tokenTtlSeconds',
      'enableConfig.debugMode',
      'enableConfig.drawEnable',
      'enableConfig.pushDrawEnable',
      'enableConfig.notifyEnable',
      'enableConfig.liveCloseNotifyEnable',
      'enableConfig.lowSpeedEnable',
      'enableConfig.translateEnable',
      'enableConfig.proxyEnable',
      'enableConfig.cacheClearEnable',
      'accountConfig.cookie',
      'accountConfig.autoFollow',
      'accountConfig.followGroup',
      'proxyConfig.proxy',
      'checkConfig.lowSpeedTime',
      'checkConfig.lowSpeedRange',
      'checkConfig.normalRange',
      'checkConfig.checkReportInterval',
      'checkConfig.timeout',
      'imageConfig.quality',
      'imageConfig.theme',
      'imageConfig.font',
      'imageConfig.defaultColor',
      'imageConfig.cardOrnament',
      'imageConfig.timeDisplayMode',
      'imageConfig.colorGenerator.hueStep',
      'imageConfig.colorGenerator.lockSB',
      'imageConfig.colorGenerator.saturation',
      'imageConfig.colorGenerator.brightness',
      'imageConfig.badgeEnable.choice',
      'templateConfig.footer.dynamicFooter',
      'templateConfig.footer.liveFooter',
      'templateConfig.footer.footerAlign',
      'cacheConfig.downloadOriginal',
      'cacheConfig.expires.DRAW',
      'cacheConfig.expires.IMAGES',
      'cacheConfig.expires.EMOJI',
      'cacheConfig.expires.USER',
      'cacheConfig.expires.OTHER',
      'pushConfig.messageInterval',
      'pushConfig.pushInterval',
      'pushConfig.toShortLink',
      'templateConfig.defaultDynamicPush',
      'templateConfig.defaultLivePush',
      'templateConfig.defaultLiveClose',
      'linkResolveConfig.triggerMode',
      'linkResolveConfig.drawEnable',
      'linkResolveConfig.returnLink',
      'adminContactQQ',
      'adminsText',
      'translateConfig.cutLine',
      'translateConfig.baidu.APP_ID',
      'translateConfig.baidu.SECURITY_KEY',
    ]))
  })

  /**
   * 前端保存前先做旧 WebUI 等价校验，避免明显错误值进入后端转换层。
   */
  it('validates editable settings before building backend payloads', () => {
    expect(validateSettingsValues({
      'platform.onebot11.port': '70000',
      'checkConfig.lowSpeedTime': 'bad',
      'checkConfig.normalRange': '120-30',
      'imageConfig.defaultColor': 'blue',
      'cacheConfig.expires.DRAW': '0',
    })).toEqual(expect.arrayContaining([
      'OneBot11 端口必须在 1-65535 之间',
      '低频时段必须使用 0-23 的 时-时 格式',
      '正常间隔必须使用 起始-结束 格式，且结束不小于起始',
      '默认颜色必须是 HEX 颜色',
      '缓存 DRAW 必须大于 0',
    ]))
  })

  /**
   * 群普通管理员由左右两栏组成，保存前必须保证每行群聊和 QQ 都是正数。
   */
  it('validates paired group admin rows', () => {
    expect(validateSettingsValues({adminsText: '123:'})).toEqual(['群普通管理员第 1 行必须同时填写正数群聊和个人QQ号'])
    expect(validateSettingsValues({adminsText: ':456'})).toEqual(['群普通管理员第 1 行必须同时填写正数群聊和个人QQ号'])
    expect(validateSettingsValues({adminsText: '123:456'})).toEqual([])
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

  /**
   * 显示层字段需要在前端转换成后端 DTO 能理解的字段名和基础类型。
   */
  it('converts display fields into backend DTO payload keys', () => {
    expect(buildSettingsSavePayload({
      file: 'biliConfig',
      snapshotToken: 'bili-token',
      confirmationPassword: 'pw',
      values: {
        'imageConfig.badgeEnable.choice': 'both',
        'cacheConfig.expires.DRAW': '7',
        'cacheConfig.expires.IMAGES': '8',
        'cacheConfig.expires.EMOJI': '9',
        'cacheConfig.expires.USER': '10',
        'cacheConfig.expires.OTHER': '11',
        'translateConfig.baidu.APP_ID': 'app-id',
        'translateConfig.baidu.SECURITY_KEY': 'secret',
      },
    })).toMatchObject({
      leftBadgeEnable: true,
      rightBadgeEnable: true,
      cacheExpires: {
        DRAW: 7,
        IMAGES: 8,
        EMOJI: 9,
        USER: 10,
        OTHER: 11,
      },
      baiduAppId: 'app-id',
      baiduSecurityKey: 'secret',
    })
  })

  /**
   * 管理员字段未进入 values 时必须保持省略，普通 bot 保存才不会覆盖用户已有管理员配置。
   */
  it('omits admins when adminsText was not submitted', () => {
    const payload = buildSettingsSavePayload({
      file: 'botConfig',
      snapshotToken: 'bot-token',
      confirmationPassword: 'pw',
      values: {
        'platform.type': 'onebot11',
        'platform.adapter': 'onebot11',
        'platform.onebot11.host': '127.0.0.1',
        'platform.onebot11.port': '3001',
      },
    })

    expect(payload).not.toHaveProperty('admins')
  })

  /**
   * 管理员字段显式提交空文本时表示清空，后端需要收到 admins: []。
   */
  it('includes empty admins when adminsText was explicitly submitted empty', () => {
    const payload = buildSettingsSavePayload({
      file: 'botConfig',
      snapshotToken: 'bot-token',
      confirmationPassword: 'pw',
      values: {
        adminsText: '',
      },
    })

    expect(payload.admins).toEqual([])
  })
})
