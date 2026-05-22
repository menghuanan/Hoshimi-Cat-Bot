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

export type SettingsFieldType = 'text' | 'number' | 'boolean' | 'textarea' | 'password'

export type SettingsFieldDefinition = {
  key: string
  label: string
  file: SettingsFileId
  payloadKey?: string
  type: SettingsFieldType
  sensitive?: boolean
  writeOnly?: boolean
  restartRequired?: boolean
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
      {key: 'platform.type', label: '平台类型', file: 'botConfig', payloadKey: 'platformType', type: 'text', restartRequired: true},
      {key: 'platform.adapter', label: '适配器', file: 'botConfig', payloadKey: 'adapter', type: 'text', restartRequired: true},
      {key: 'platform.onebot11.host', label: 'OneBot11 主机', file: 'botConfig', payloadKey: 'oneBot11Host', type: 'text', restartRequired: true},
      {key: 'platform.onebot11.port', label: 'OneBot11 端口', file: 'botConfig', payloadKey: 'oneBot11Port', type: 'number', restartRequired: true},
      {key: 'platform.onebot11.token', label: 'OneBot11 Token', file: 'botConfig', payloadKey: 'oneBot11Token', type: 'password', sensitive: true, writeOnly: true, restartRequired: true},
      {key: 'platform.onebot11.useTls', label: '启用 TLS', file: 'botConfig', payloadKey: 'oneBot11UseTls', type: 'boolean', restartRequired: true},
    ],
  },
  {
    id: 'feature',
    label: '功能开关',
    fields: [
      {key: 'enableConfig.drawEnable', label: '动态渲染', file: 'biliConfig', payloadKey: 'drawEnable', type: 'boolean'},
      {key: 'enableConfig.pushDrawEnable', label: '推送渲染', file: 'biliConfig', payloadKey: 'pushDrawEnable', type: 'boolean'},
      {key: 'enableConfig.notifyEnable', label: '直播通知', file: 'biliConfig', payloadKey: 'notifyEnable', type: 'boolean'},
      {key: 'enableConfig.translateEnable', label: '翻译功能', file: 'biliConfig', payloadKey: 'translateEnable', type: 'boolean'},
      {key: 'enableConfig.proxyEnable', label: '启用代理', file: 'biliConfig', payloadKey: 'proxyEnable', type: 'boolean'},
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
      {key: 'checkConfig.timeout', label: '请求超时', file: 'biliConfig', payloadKey: 'timeout', type: 'number'},
    ],
  },
  {
    id: 'render',
    label: '渲染配置',
    fields: [
      {key: 'renderConfig.quality', label: '图片质量', file: 'biliConfig', payloadKey: 'quality', type: 'text'},
      {key: 'renderConfig.theme', label: '主题', file: 'biliConfig', payloadKey: 'theme', type: 'text'},
      {key: 'renderConfig.font', label: '字体', file: 'biliConfig', payloadKey: 'font', type: 'text'},
      {key: 'renderConfig.defaultColor', label: '默认颜色', file: 'biliConfig', payloadKey: 'defaultColor', type: 'text'},
      {key: 'renderConfig.timeDisplayMode', label: '时间显示', file: 'biliConfig', payloadKey: 'timeDisplayMode', type: 'text'},
    ],
  },
  {
    id: 'message',
    label: '消息配置',
    fields: [
      {key: 'pushConfig.messageInterval', label: '消息间隔', file: 'biliConfig', payloadKey: 'messageInterval', type: 'number'},
      {key: 'pushConfig.pushInterval', label: '推送间隔', file: 'biliConfig', payloadKey: 'pushInterval', type: 'number'},
      {key: 'pushConfig.defaultDynamicPush', label: '动态模板', file: 'biliConfig', payloadKey: 'defaultDynamicPush', type: 'text'},
      {key: 'pushConfig.defaultLivePush', label: '直播模板', file: 'biliConfig', payloadKey: 'defaultLivePush', type: 'text'},
      {key: 'pushConfig.defaultLiveClose', label: '下播模板', file: 'biliConfig', payloadKey: 'defaultLiveClose', type: 'text'},
    ],
  },
  {
    id: 'admin',
    label: '管理员',
    fields: [
      {key: 'admin', label: '超级管理员', file: 'biliConfig', payloadKey: 'admin', type: 'number'},
      {key: 'adminContact', label: '管理员联系人', file: 'biliConfig', payloadKey: 'adminContact', type: 'text'},
      {key: 'webui.enabled', label: '启用 WebUI', file: 'botConfig', payloadKey: 'webUiEnabled', type: 'boolean', restartRequired: true},
      {key: 'webui.host', label: 'WebUI 主机', file: 'botConfig', payloadKey: 'webUiHost', type: 'text', restartRequired: true},
      {key: 'webui.port', label: 'WebUI 端口', file: 'botConfig', payloadKey: 'webUiPort', type: 'number', restartRequired: true},
    ],
  },
  {
    id: 'translate',
    label: '翻译配置',
    fields: [
      {key: 'translateConfig.triggerMode', label: '触发模式', file: 'biliConfig', payloadKey: 'triggerMode', type: 'text'},
      {key: 'translateConfig.baiduAppId', label: '百度 App ID', file: 'biliConfig', payloadKey: 'baiduAppId', type: 'password', sensitive: true, writeOnly: true},
      {key: 'translateConfig.baiduSecurityKey', label: '百度密钥', file: 'biliConfig', payloadKey: 'baiduSecurityKey', type: 'password', sensitive: true, writeOnly: true},
      {key: 'translateConfig.cutLine', label: '翻译分隔线', file: 'biliConfig', payloadKey: 'cutLine', type: 'textarea'},
    ],
  },
]

/**
 * 字段查找表服务 payload builder 和设置页渲染，保持 key 到 DTO 字段的单点映射。
 */
export const settingsFieldByKey = new Map(
  settingsCategories.flatMap((category) => category.fields).map((field) => [field.key, field]),
)
