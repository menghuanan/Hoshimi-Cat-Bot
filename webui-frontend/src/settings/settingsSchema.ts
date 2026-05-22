export type SettingsFileId = 'biliConfig' | 'botConfig'

export type SettingsCategoryId =
  | 'integration'
  | 'feature'
  | 'bili'
  | 'polling'
  | 'render'
  | 'message'
  | 'admin'
  | 'translate'

export type SettingsFieldType = 'text' | 'number' | 'boolean' | 'textarea' | 'password' | 'select'

export type SettingsFieldOption = {
  value: string
  label: string
}

export type SettingsFieldDefinition = {
  key: string
  label: string
  file: SettingsFileId
  payloadKey?: string
  type: SettingsFieldType
  sensitive?: boolean
  writeOnly?: boolean
  restartRequired?: boolean
  options?: SettingsFieldOption[]
  min?: number
  max?: number
  step?: number
  placeholder?: string
}

export type SettingsCategoryDefinition = {
  id: SettingsCategoryId
  label: string
  fields: SettingsFieldDefinition[]
}

/**
 * 设置页元数据是 React 渲染和保存 payload 的共同来源，避免页面分区和字段映射漂移。
 */
export const settingsCategories: SettingsCategoryDefinition[] = [
  {
    id: 'integration',
    label: '对接配置',
    fields: [
      {key: 'platform.type', label: '平台类型', file: 'botConfig', payloadKey: 'platformType', type: 'select', restartRequired: true, options: [
        {value: 'onebot11', label: '通用机器人协议'},
        {value: 'qq_official', label: 'QQ 官方机器人'},
      ]},
      {key: 'platform.adapter', label: '适配器', file: 'botConfig', payloadKey: 'adapter', type: 'select', restartRequired: true, options: [
        {value: 'onebot11', label: '通用'},
        {value: 'napcat', label: 'NapCat'},
        {value: 'llbot', label: 'llbot'},
      ]},
      {key: 'platform.onebot11.host', label: 'OneBot11 主机', file: 'botConfig', payloadKey: 'oneBot11Host', type: 'text', restartRequired: true},
      {key: 'platform.onebot11.port', label: 'OneBot11 端口', file: 'botConfig', payloadKey: 'oneBot11Port', type: 'number', restartRequired: true},
      {key: 'platform.onebot11.token', label: 'OneBot11 Token', file: 'botConfig', payloadKey: 'oneBot11Token', type: 'password', sensitive: true, writeOnly: true, restartRequired: true},
      {key: 'platform.onebot11.useTls', label: '启用 TLS', file: 'botConfig', payloadKey: 'oneBot11UseTls', type: 'boolean', restartRequired: true},
      {key: 'platform.onebot11.heartbeatInterval', label: '心跳间隔', file: 'botConfig', payloadKey: 'oneBot11HeartbeatInterval', type: 'number', restartRequired: true, min: 1},
      {key: 'platform.onebot11.reconnectInterval', label: '重连间隔', file: 'botConfig', payloadKey: 'oneBot11ReconnectInterval', type: 'number', restartRequired: true, min: 1},
      {key: 'platform.onebot11.sendMode', label: '图片发送方式', file: 'botConfig', payloadKey: 'oneBot11SendMode', type: 'select', restartRequired: true, options: [
        {value: 'base64', label: 'base64'},
        {value: 'file', label: 'file'},
      ]},
      {key: 'platform.onebot11.maxReconnectAttempts', label: '最大重连次数', file: 'botConfig', payloadKey: 'oneBot11MaxReconnectAttempts', type: 'number', restartRequired: true},
      {key: 'platform.onebot11.connectTimeout', label: '连接超时', file: 'botConfig', payloadKey: 'oneBot11ConnectTimeout', type: 'number', restartRequired: true, min: 1},
      {key: 'platform.qqOfficial.appId', label: 'QQ App ID', file: 'botConfig', payloadKey: 'qqOfficialAppId', type: 'text', restartRequired: true},
      {key: 'platform.qqOfficial.appSecret', label: 'QQ App Secret', file: 'botConfig', payloadKey: 'qqOfficialAppSecret', type: 'password', sensitive: true, writeOnly: true, restartRequired: true},
      {key: 'platform.qqOfficial.botToken', label: 'QQ Bot Token', file: 'botConfig', payloadKey: 'qqOfficialBotToken', type: 'password', sensitive: true, writeOnly: true, restartRequired: true},
      {key: 'webui.enabled', label: '启用 WebUI', file: 'botConfig', payloadKey: 'webUiEnabled', type: 'boolean', restartRequired: true},
      {key: 'webui.host', label: 'WebUI 主机', file: 'botConfig', payloadKey: 'webUiHost', type: 'text', restartRequired: true},
      {key: 'webui.port', label: 'WebUI 端口', file: 'botConfig', payloadKey: 'webUiPort', type: 'number', restartRequired: true, min: 1, max: 65535},
      {key: 'webui.tokenTtlSeconds', label: '会话有效秒数', file: 'botConfig', payloadKey: 'webUiTokenTtlSeconds', type: 'number', min: 1},
    ],
  },
  {
    id: 'feature',
    label: '功能开关',
    fields: [
      {key: 'enableConfig.debugMode', label: '调试模式', file: 'biliConfig', payloadKey: 'debugMode', type: 'boolean'},
      {key: 'enableConfig.drawEnable', label: '动态渲染', file: 'biliConfig', payloadKey: 'drawEnable', type: 'boolean'},
      {key: 'enableConfig.pushDrawEnable', label: '推送渲染', file: 'biliConfig', payloadKey: 'pushDrawEnable', type: 'boolean'},
      {key: 'enableConfig.notifyEnable', label: '直播通知', file: 'biliConfig', payloadKey: 'notifyEnable', type: 'boolean'},
      {key: 'enableConfig.liveCloseNotifyEnable', label: '下播通知', file: 'biliConfig', payloadKey: 'liveCloseNotifyEnable', type: 'boolean'},
      {key: 'enableConfig.lowSpeedEnable', label: '低频模式', file: 'biliConfig', payloadKey: 'lowSpeedEnable', type: 'boolean'},
      {key: 'enableConfig.translateEnable', label: '翻译功能', file: 'biliConfig', payloadKey: 'translateEnable', type: 'boolean'},
      {key: 'enableConfig.proxyEnable', label: '启用代理', file: 'biliConfig', payloadKey: 'proxyEnable', type: 'boolean'},
      {key: 'enableConfig.cacheClearEnable', label: '缓存清理', file: 'biliConfig', payloadKey: 'cacheClearEnable', type: 'boolean'},
    ],
  },
  {
    id: 'bili',
    label: 'B站配置',
    fields: [
      {key: 'accountConfig.cookie', label: 'Cookie', file: 'biliConfig', payloadKey: 'cookie', type: 'password', sensitive: true, writeOnly: true},
      {key: 'accountConfig.autoFollow', label: '自动关注', file: 'biliConfig', payloadKey: 'autoFollow', type: 'boolean'},
      {key: 'accountConfig.followGroup', label: '关注分组', file: 'biliConfig', payloadKey: 'followGroup', type: 'text'},
      {key: 'proxyConfig.proxy', label: '代理地址', file: 'biliConfig', payloadKey: 'proxies', type: 'textarea', sensitive: true, writeOnly: true},
    ],
  },
  {
    id: 'polling',
    label: '轮询配置',
    fields: [
      {key: 'checkConfig.lowSpeedTime', label: '低速时段', file: 'biliConfig', payloadKey: 'lowSpeedTime', type: 'text'},
      {key: 'checkConfig.lowSpeedRange', label: '低速范围', file: 'biliConfig', payloadKey: 'lowSpeedRange', type: 'text'},
      {key: 'checkConfig.normalRange', label: '正常范围', file: 'biliConfig', payloadKey: 'normalRange', type: 'text'},
      {key: 'checkConfig.checkReportInterval', label: '状态报告间隔', file: 'biliConfig', payloadKey: 'checkReportInterval', type: 'number', min: 1},
      {key: 'checkConfig.timeout', label: '请求超时', file: 'biliConfig', payloadKey: 'timeout', type: 'number'},
    ],
  },
  {
    id: 'render',
    label: '渲染配置',
    fields: [
      {key: 'imageConfig.quality', label: '图片质量', file: 'biliConfig', payloadKey: 'quality', type: 'text'},
      {key: 'imageConfig.theme', label: '主题', file: 'biliConfig', payloadKey: 'theme', type: 'text'},
      {key: 'imageConfig.font', label: '字体', file: 'biliConfig', payloadKey: 'font', type: 'text'},
      {key: 'imageConfig.defaultColor', label: '默认颜色', file: 'biliConfig', payloadKey: 'defaultColor', type: 'text', placeholder: '#d3edfa'},
      {key: 'imageConfig.cardOrnament', label: '右侧装饰', file: 'biliConfig', payloadKey: 'cardOrnament', type: 'select', options: [
        {value: 'FanCard', label: '粉丝卡'},
        {value: 'QrCode', label: '二维码'},
        {value: '', label: '不绘制'},
      ]},
      {key: 'imageConfig.timeDisplayMode', label: '时间显示', file: 'biliConfig', payloadKey: 'timeDisplayMode', type: 'select', options: [
        {value: 'ABSOLUTE', label: '绝对时间'},
        {value: 'RELATIVE', label: '相对时间'},
      ]},
      {key: 'imageConfig.colorGenerator.hueStep', label: '色相步进', file: 'biliConfig', payloadKey: 'hueStep', type: 'number'},
      {key: 'imageConfig.colorGenerator.lockSB', label: '锁定明度饱和', file: 'biliConfig', payloadKey: 'lockSB', type: 'boolean'},
      {key: 'imageConfig.colorGenerator.saturation', label: '饱和度', file: 'biliConfig', payloadKey: 'saturation', type: 'number', step: 0.01},
      {key: 'imageConfig.colorGenerator.brightness', label: '亮度', file: 'biliConfig', payloadKey: 'brightness', type: 'number', step: 0.01},
      {key: 'imageConfig.badgeEnable.choice', label: '徽章', file: 'biliConfig', type: 'select', options: [
        {value: 'left', label: '左徽章'},
        {value: 'right', label: '右徽章'},
        {value: 'both', label: '左右徽章'},
        {value: 'none', label: '不显示'},
      ]},
      {key: 'templateConfig.footer.dynamicFooter', label: '动态页脚', file: 'biliConfig', payloadKey: 'dynamicFooter', type: 'textarea'},
      {key: 'templateConfig.footer.liveFooter', label: '直播页脚', file: 'biliConfig', payloadKey: 'liveFooter', type: 'textarea'},
      {key: 'templateConfig.footer.footerAlign', label: '页脚对齐', file: 'biliConfig', payloadKey: 'footerAlign', type: 'select', options: [
        {value: 'LEFT', label: '左'},
        {value: 'CENTER', label: '中'},
        {value: 'RIGHT', label: '右'},
      ]},
      {key: 'cacheConfig.downloadOriginal', label: '下载原图', file: 'biliConfig', payloadKey: 'downloadOriginal', type: 'boolean'},
      {key: 'cacheConfig.expires.DRAW', label: '缓存 DRAW', file: 'biliConfig', type: 'number', min: 1},
      {key: 'cacheConfig.expires.IMAGES', label: '缓存 IMAGES', file: 'biliConfig', type: 'number', min: 1},
      {key: 'cacheConfig.expires.EMOJI', label: '缓存 EMOJI', file: 'biliConfig', type: 'number', min: 1},
      {key: 'cacheConfig.expires.USER', label: '缓存 USER', file: 'biliConfig', type: 'number', min: 1},
      {key: 'cacheConfig.expires.OTHER', label: '缓存 OTHER', file: 'biliConfig', type: 'number', min: 1},
    ],
  },
  {
    id: 'message',
    label: '消息配置',
    fields: [
      {key: 'pushConfig.messageInterval', label: '消息间隔', file: 'biliConfig', payloadKey: 'messageInterval', type: 'number'},
      {key: 'pushConfig.pushInterval', label: '推送间隔', file: 'biliConfig', payloadKey: 'pushInterval', type: 'number'},
      {key: 'pushConfig.toShortLink', label: '转短链', file: 'biliConfig', payloadKey: 'toShortLink', type: 'boolean'},
      {key: 'templateConfig.defaultDynamicPush', label: '动态默认模板', file: 'biliConfig', payloadKey: 'defaultDynamicPush', type: 'text'},
      {key: 'templateConfig.defaultLivePush', label: '直播默认模板', file: 'biliConfig', payloadKey: 'defaultLivePush', type: 'text'},
      {key: 'templateConfig.defaultLiveClose', label: '下播默认模板', file: 'biliConfig', payloadKey: 'defaultLiveClose', type: 'text'},
      {key: 'linkResolveConfig.triggerMode', label: '链接解析触发', file: 'biliConfig', payloadKey: 'triggerMode', type: 'select', options: [
        {value: 'At', label: '被提及时'},
        {value: 'Always', label: '总是'},
        {value: 'Never', label: '关闭'},
      ]},
      {key: 'linkResolveConfig.drawEnable', label: '链接解析绘图', file: 'biliConfig', payloadKey: 'linkResolveDrawEnable', type: 'boolean'},
      {key: 'linkResolveConfig.returnLink', label: '返回链接', file: 'biliConfig', payloadKey: 'linkResolveReturnLink', type: 'boolean'},
    ],
  },
  {
    id: 'admin',
    label: '管理员',
    fields: [
      {key: 'adminContactQQ', label: '超级管理员 QQ', file: 'biliConfig', payloadKey: 'adminContact', type: 'number', min: 1},
      {key: 'adminsText', label: '群普通管理员', file: 'botConfig', payloadKey: 'admins', type: 'textarea'},
    ],
  },
  {
    id: 'translate',
    label: '翻译配置',
    fields: [
      {key: 'translateConfig.cutLine', label: '翻译分隔线', file: 'biliConfig', payloadKey: 'cutLine', type: 'textarea'},
      {key: 'translateConfig.baidu.APP_ID', label: '百度 APP ID', file: 'biliConfig', payloadKey: 'baiduAppId', type: 'text'},
      {key: 'translateConfig.baidu.SECURITY_KEY', label: '百度密钥', file: 'biliConfig', payloadKey: 'baiduSecurityKey', type: 'password', sensitive: true, writeOnly: true},
    ],
  },
]

/**
 * 字段查找表服务 payload builder 和设置页渲染，保持 key 到 DTO 字段的单点映射。
 */
export const settingsFieldByKey = new Map(
  settingsCategories.flatMap((category) => category.fields).map((field) => [field.key, field]),
)

/**
 * 设置页保存前执行浏览器侧纠错，先拦截明显非法值再交给后端做最终校验。
 */
export function validateSettingsValues(values: Record<string, unknown>): string[] {
  return [
    ...validatePort('OneBot11 端口', values['platform.onebot11.port']),
    ...validatePort('WebUI 端口', values['webui.port']),
    ...validateHourRange('低频时段', values['checkConfig.lowSpeedTime']),
    ...validateIntervalRange('低频间隔', values['checkConfig.lowSpeedRange']),
    ...validateIntervalRange('正常间隔', values['checkConfig.normalRange']),
    ...validateHexColor('默认颜色', values['imageConfig.defaultColor']),
    ...['DRAW', 'IMAGES', 'EMOJI', 'USER', 'OTHER'].flatMap((key) => validatePositiveInteger(`缓存 ${key}`, values[`cacheConfig.expires.${key}`])),
    ...validateAdminLines(values.adminsText),
  ]
}

/**
 * 正整数校验只处理用户当前填写的值，未显示字段不会因为缺省而阻断保存。
 */
function validatePositiveInteger(label: string, value: unknown): string[] {
  const text = String(value ?? '').trim()
  if (!text) return []
  if (!/^\d+$/.test(text) || Number.parseInt(text, 10) <= 0) return [`${label} 必须大于 0`]
  return []
}

/**
 * 端口校验按系统端口范围限制，避免明显不可启动的连接参数进入后端。
 */
function validatePort(label: string, value: unknown): string[] {
  const text = String(value ?? '').trim()
  if (!text) return []
  const port = Number.parseInt(text, 10)
  if (!/^\d+$/.test(text) || port < 1 || port > 65535) return [`${label}必须在 1-65535 之间`]
  return []
}

/**
 * 小时范围允许跨午夜，但两端都必须是 0-23 内的整数。
 */
function validateHourRange(label: string, value: unknown): string[] {
  const range = parseRange(value)
  if (!range || range.start < 0 || range.start > 23 || range.end < 0 || range.end > 23 || range.start === range.end) {
    return [`${label}必须使用 0-23 的 时-时 格式`]
  }
  return []
}

/**
 * 秒级区间必须是升序正整数，前端不再把错误文本隐式回退成默认值。
 */
function validateIntervalRange(label: string, value: unknown): string[] {
  const range = parseRange(value)
  if (!range || range.start <= 0 || range.end <= 0 || range.end < range.start) {
    return [`${label}必须使用 起始-结束 格式，且结束不小于起始`]
  }
  return []
}

/**
 * 颜色字段兼容单色和分号分隔渐变色，单段仍必须是 #RRGGBB。
 */
function validateHexColor(label: string, value: unknown): string[] {
  const text = String(value ?? '').trim()
  if (!text) return []
  const valid = text.split(/[;；]/).every((segment) => /^#[0-9A-Fa-f]{6}$/.test(segment.trim()))
  return valid ? [] : [`${label}必须是 HEX 颜色`]
}

/**
 * 管理员文本沿用“群号:QQ号”逐行格式，错误行会在前端直接指出。
 */
function validateAdminLines(value: unknown): string[] {
  const lines = String(value ?? '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  const invalidIndex = lines.findIndex((line) => !/^\d+\s*[:：]\s*\d+$/.test(line))
  return invalidIndex >= 0 ? [`群普通管理员第 ${invalidIndex + 1} 行格式应为 群号:QQ号`] : []
}

/**
 * 区间解析保持窄格式，避免中文单位或小数被错误转交给后端配置。
 */
function parseRange(value: unknown): {start: number, end: number} | null {
  const matched = String(value ?? '').trim().match(/^(\d+)\s*-\s*(\d+)$/)
  if (!matched) return null
  return {start: Number.parseInt(matched[1], 10), end: Number.parseInt(matched[2], 10)}
}
