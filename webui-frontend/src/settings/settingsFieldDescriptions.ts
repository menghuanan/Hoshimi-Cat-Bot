/**
 * 字段说明统一集中在这里，渲染层只负责把文案贴到输入卡片底部。
 */
export const settingsFieldDescriptions: Record<string, string> = {
  // 对接配置
  'platform.type': '当前 QQ 官方机器人没有做适配，不推荐使用。',
  'platform.adapter': '优先选择 NapCat 或 llbot；只有兼容现有环境时才改动。',
  'platform.onebot11.host': '填写 OneBot11 主机地址，同机部署时通常使用 127.0.0.1。',
  'platform.onebot11.port': '填写 OneBot11 实际开放端口。',
  'platform.onebot11.token': '仅在 OneBot11 侧启用了 token 时填写，默认可留空。',
  'platform.onebot11.useTls': '按 OneBot11 侧是否启用 TLS 决定，通常保持关闭。',
  'platform.onebot11.heartbeatInterval': '保持默认即可，连接异常时再对照 OneBot11 侧默认值调整。',
  'platform.onebot11.reconnectInterval': '保持默认即可，连接异常时再对照 OneBot11 侧默认值调整。',
  'platform.onebot11.sendMode': '保持默认发送方式即可，只有有特殊传输需求时再切换。',
  'platform.onebot11.maxReconnectAttempts': '设置为正整数可限制重连次数，默认值一般即可。',
  'platform.onebot11.connectTimeout': '连接建立超时时间，通常保持默认即可。',
  'platform.qqOfficial.appId': '填写 QQ 官方机器人应用的 App ID。',
  'platform.qqOfficial.appSecret': '填写 QQ 官方机器人应用密钥。',
  'platform.qqOfficial.botToken': '填写 QQ 官方机器人 Bot Token。',
  'webui.enabled': '默认关闭，按需开启独立 WebUI。',
  'webui.host': '保持默认即可；如果改成 0.0.0.0，就会把 WebUI 对外暴露到所有网卡并需要额外确认。',
  'webui.port': '建议使用非常用端口，避免与本机其他服务冲突。',
  'webui.tokenTtlSeconds': '控制登录会话有效时长，保持默认通常最稳妥。',

  // 功能开关
  'enableConfig.debugMode': '开启后日志会显示 debug 级别内容，便于排障。',
  'enableConfig.drawEnable': '控制动态类推送是否渲染成图片发送。',
  'enableConfig.pushDrawEnable': '控制推送类内容是否渲染成图片发送。',
  'enableConfig.notifyEnable': '控制直播通知推送开关。',
  'enableConfig.liveCloseNotifyEnable': '控制下播通知推送开关。',
  'enableConfig.lowSpeedEnable': '开启后使用低频轮询，降低被风控的概率。',
  'enableConfig.translateEnable': '开启后还需要在翻译配置中补齐翻译 key。',
  'enableConfig.proxyEnable': '开启后会使用代理模式访问外部资源。',
  'enableConfig.cacheClearEnable': '开启后会自动清理图片缓存。',

  // B站配置
  'accountConfig.cookie': '不建议直接在这里填写 Cookie，优先使用 /登录。',
  'accountConfig.autoFollow': '控制账号是否自动关注已订阅的 UP 主。',
  'accountConfig.followGroup': '关注成功后使用的分组名称。',
  'proxyConfig.proxy': '填写代理地址，当前前端无法校验其可达性。',

  // 轮询配置
  'checkConfig.lowSpeedTime': '填写小时范围，区间内会切换为低速轮询。',
  'checkConfig.lowSpeedRange': '填写秒级随机区间，轮询间隔会在该范围内随机选择。',
  'checkConfig.normalRange': '填写秒级随机区间，轮询间隔会在该范围内随机选择。',
  'checkConfig.checkReportInterval': '状态检查的汇报间隔，按需调整即可。',
  'checkConfig.timeout': '请求 API 的超时时间。',

  // 渲染配置
  'imageConfig.quality': '可选 1000W、800W、500W 等分辨率档位。',
  'imageConfig.theme': '不清楚作用时不要改动。',
  'imageConfig.font': '留空会使用默认内置字体。',
  'imageConfig.defaultColor': '作为渲染图片底部的默认颜色样式。',
  'imageConfig.cardOrnament': '控制动态类图片右侧的装饰样式。',
  'imageConfig.timeDisplayMode': '控制图片内时间的显示方式。',
  'imageConfig.colorGenerator.hueStep': '不清楚作用时不要改动。',
  'imageConfig.colorGenerator.lockSB': '不清楚作用时不要改动。',
  'imageConfig.colorGenerator.saturation': '控制自动生成颜色的饱和度。',
  'imageConfig.colorGenerator.brightness': '控制自动生成颜色的亮度。',
  'imageConfig.badgeEnable.choice': '控制徽章显示位置，不熟悉时保持默认即可。',
  'templateConfig.footer.dynamicFooter': '动态图片底部额外渲染的字段内容。',
  'templateConfig.footer.liveFooter': '直播图片底部额外渲染的字段内容。',
  'templateConfig.footer.footerAlign': '控制页脚内容的对齐方式。',
  'cacheConfig.downloadOriginal': '开启后会下载原图，通常能获得更好的显示效果。',
  'cacheConfig.expires.DRAW': '缓存图片的最大保留天数，单位天。',
  'cacheConfig.expires.IMAGES': '缓存图片的最大保留天数，单位天。',
  'cacheConfig.expires.EMOJI': '缓存图片的最大保留天数，单位天。',
  'cacheConfig.expires.USER': '缓存图片的最大保留天数，单位天。',
  'cacheConfig.expires.OTHER': '缓存图片的最大保留天数，单位天。',

  // 消息配置
  'pushConfig.messageInterval': '发送消息之间的间隔，单位毫秒。',
  'pushConfig.pushInterval': '推送消息之间的间隔，单位毫秒。',
  'pushConfig.toShortLink': '将获取到的短链接转成正常长链接。',
  'templateConfig.defaultDynamicPush': '动态推送默认使用的模板名称，可选DrawOnly/TextOnly/OneMsg/TwoMsg。',
  'templateConfig.defaultLivePush': '直播推送默认使用的模板名称，可选DrawOnly/TextOnly/OneMsg/TwoMsg。',
  'templateConfig.defaultLiveClose': '下播通知默认使用的模板名称，可选可选SimpleMsg/ComplexMsg。',
  'linkResolveConfig.triggerMode': '控制链接解析的触发方式。',
  'linkResolveConfig.drawEnable': '控制解析后的链接是否转成图片。',
  'linkResolveConfig.returnLink': '控制解析后是否保留链接文本。',
  'linkParseBlacklistContacts': '逐行填写联系人 subject，被列入的群聊或用户不会触发链接解析。',

  // 管理员
  'adminContactQQ': '最高权限管理员 QQ，建议填写自己的号码。',
  'adminsText': '按“群号:QQ号”逐行配置群聊内可操控 bot 的管理员。',

  // 翻译配置
  'translateConfig.cutLine': '正文与译文之间的分隔线样式。',
  'translateConfig.baidu.APP_ID': '填写百度翻译接口的 APP ID。',
  'translateConfig.baidu.SECURITY_KEY': '填写百度翻译接口的密钥。',
}
