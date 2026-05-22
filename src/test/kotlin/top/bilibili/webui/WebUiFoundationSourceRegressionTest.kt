package top.bilibili.webui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUiFoundationSourceRegressionTest {
    /**
     * 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
     */
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    /**
     * 样式块断言只检查目标选择器，避免全局同名属性让布局回归测试误判。
     */
    private fun cssBlock(source: String, selector: String): String {
        val pattern = Regex("""(?s)${Regex.escape(selector)}\s*\{(.*?)\}""")
        return pattern.find(source)?.groupValues?.get(1).orEmpty()
    }

    /**
     * 首页统计卡片按标题截取整段 HTML，避免只靠全局字符串误判某张卡片的右上角控件。
     */
    private fun metricCardBlock(source: String, title: String): String {
        val pattern = Regex("""(?s)<article class="metric-card[^"]*">.*?</article>""")
        return pattern.findAll(source).firstOrNull { match -> match.value.contains(title) }?.value.orEmpty()
    }

    @Test
    fun `webui config should stay disabled by default and remain part of bot runtime config`() {
        val configSource = read("src/main/kotlin/top/bilibili/config/NapCatConfig.kt")
        val webUiConfigSource = read("src/main/kotlin/top/bilibili/webui/config/WebUiConfig.kt")

        assertTrue(configSource.contains("val webui: WebUiConfig = WebUiConfig()"))
        assertTrue(webUiConfigSource.contains("""val enabled: Boolean = false"""))
        assertTrue(webUiConfigSource.contains("""val host: String = "127.0.0.1""""))
        assertTrue(webUiConfigSource.contains("""val port: Int = 18080"""))
        assertTrue(webUiConfigSource.contains("""@SerialName("static_dir")"""))
    }

    @Test
    fun `webui routes should reserve root assets and api boundaries`() {
        val apiRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiApiRoutes.kt")
        val staticRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiStaticRoutes.kt")
        val authRoutes = read("src/main/kotlin/top/bilibili/webui/routes/WebUiAuthRoutes.kt")
        val runtimeDtos = read("src/main/kotlin/top/bilibili/webui/model/WebUiRuntimeDtos.kt")
        val runtimeFacade = read("src/main/kotlin/top/bilibili/webui/service/WebUiRuntimeFacade.kt")

        assertTrue(apiRoutes.contains("/api/runtime/summary"))
        assertTrue(apiRoutes.contains("/api/config/bili-config"))
        assertTrue(apiRoutes.contains("/api/subscriptions"))
        assertTrue(runtimeDtos.contains("""val appVersion: String"""))
        assertTrue(runtimeFacade.contains("""appVersion = appVersionProvider()"""))
        assertTrue(authRoutes.contains("/api/auth/login"))
        assertTrue(authRoutes.contains("/api/auth/change-password"))
        assertTrue(staticRoutes.contains("""get("/")"""))
        assertTrue(staticRoutes.contains("""get("/login")"""))
    }

    @Test
    fun `webui server and route logic should stay out of core lifecycle wiring`() {
        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")
        val managerSource = read("src/main/kotlin/top/bilibili/webui/server/WebUiManager.kt")

        assertTrue(botSource.contains("WebUiManager"))
        assertFalse(botSource.contains("embeddedServer("))
        assertFalse(botSource.contains("/api/health"))
        assertTrue(managerSource.contains("embeddedServer("))
    }

    @Test
    fun `frontend shell should expose the static dashboard sections`() {
        val loginPage = read("src/main/resources/webui/login.html")
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val authScript = read("src/main/resources/webui/assets/auth.js")

        assertTrue(loginPage.contains("id=\"login-form\""))
        assertTrue(loginPage.contains("id=\"change-password-form\""))
        assertTrue(loginPage.contains("theme.js"))
        assertTrue(shellPage.contains("data-nav-target=\"home\""))
        assertTrue(shellPage.contains("data-nav-target=\"settings\""))
        assertTrue(shellPage.contains("data-nav-target=\"subscriptions\""))
        assertTrue(shellPage.contains("data-nav-target=\"logs\""))
        assertTrue(shellPage.contains("class=\"metric-grid\""))
        assertTrue(shellPage.contains("class=\"dashboard-grid\""))
        assertTrue(shellPage.contains("config-grid"))
        assertTrue(shellPage.contains("class=\"subscription-grid\""))
        assertTrue(shellPage.contains("class=\"log-list\""))
        assertTrue(shellPage.contains("Bot 运行状态"))
        assertTrue(shellPage.contains("系统配置"))
        assertTrue(shellPage.contains("订阅管理"))
        assertTrue(shellPage.contains("日志"))
        assertFalse(shellPage.contains("data-nav-target=\"features\""))
        assertFalse(shellPage.contains("page-features"))
        assertFalse(shellPage.contains("feature-grid"))
        assertTrue(shellPage.contains("""data-runtime-field="startedAt""""))
        assertTrue(shellPage.contains("""data-runtime-field="runtimeDuration""""))
        assertTrue(shellPage.contains("""data-runtime-field="systemTime""""))
        assertTrue(shellPage.contains("""data-runtime-field="systemLoad""""))
        assertTrue(shellPage.contains("""data-runtime-field="cpuUsage""""))
        assertTrue(shellPage.contains("""data-runtime-field="memoryUsage""""))
        assertTrue(shellPage.contains("""data-runtime-field="storageUsage""""))
        assertTrue(shellPage.contains("""data-runtime-field="dockerStatus""""))
        assertTrue(shellPage.contains("""data-runtime-field="sidebarVersion""""))
        assertTrue(shellPage.contains("""data-runtime-list="recentPushRecords""""))
        assertTrue(shellPage.contains("""data-theme-option="dark""""))
        assertTrue(shellPage.contains("""data-theme-option="light""""))
        assertTrue(shellPage.contains("""data-theme-option="system""""))
        assertTrue(shellPage.contains("""id="theme-mode-selector""""))
        assertFalse(shellPage.contains("""class="topbar-icon-wrap""""))
        assertFalse(shellPage.contains("""class="badge""""))
        assertFalse(shellPage.contains("""class="status-chip""""))
        assertFalse(shellPage.contains("""data-runtime-field="sidebarStatus""""))
        assertFalse(shellPage.contains("查看全部"))
        assertTrue(shellPage.contains("""data-runtime-progress="cpuUsage""""))
        assertTrue(shellPage.contains("""data-runtime-progress="memoryUsage""""))
        assertTrue(shellPage.contains("""data-runtime-progress="storageUsage""""))
        assertTrue(shellPage.contains("theme.js"))
        assertTrue(shellScript.contains("activateView("))
        assertTrue(shellScript.contains("hashchange"))
        assertTrue(shellScript.contains("history.replaceState"))
        assertTrue(shellScript.contains("/api/runtime/summary"))
        assertTrue(shellScript.contains("refreshRuntimeSummary"))
        assertTrue(shellScript.contains("renderHostRuntimeStatus"))
        assertTrue(shellScript.contains("renderRecentPushRecords"))
        assertTrue(shellScript.contains("dynamic_bot_webui_theme"))
        assertTrue(shellScript.contains("applyThemePreference"))
        assertTrue(shellScript.contains("resolveThemePreference"))
        assertTrue(authScript.contains("dynamic_bot_webui_theme"))
        assertTrue(authScript.contains("applyThemePreference"))
        assertTrue(authScript.contains("/api/auth/login"))
        assertTrue(authScript.contains("/api/auth/change-password"))
    }

    /**
     * 首页统计卡只保留用户指定的三个跳转入口，Bot 状态和今日推送统计不再显示右上角符号。
     */
    @Test
    fun `home metric cards should expose only requested navigation shortcuts`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        val botCard = metricCardBlock(shellPage, "Bot 运行状态")
        val subscriptionCard = metricCardBlock(shellPage, "当前订阅数量")
        val biliCard = metricCardBlock(shellPage, "B站账号信息")
        val websocketCard = metricCardBlock(shellPage, "WebSocket 状态")
        val pushStatsCard = metricCardBlock(shellPage, "今日推送统计")

        assertFalse(botCard.contains("""class="metric-menu""""))
        assertFalse(pushStatsCard.contains("""class="metric-menu""""))
        assertFalse(pushStatsCard.contains("""data-metric-nav"""))
        assertTrue(subscriptionCard.contains("""data-metric-nav="subscriptions""""))
        assertTrue(biliCard.contains("""data-metric-nav="settings""""))
        assertTrue(biliCard.contains("""data-settings-tab-target="bili""""))
        assertTrue(websocketCard.contains("""data-metric-nav="settings""""))
        assertTrue(websocketCard.contains("""data-settings-tab-target="integration""""))
        assertTrue(shellScript.contains("metricNavButtons"))
        assertTrue(shellScript.contains("navigateMetricShortcut"))
        assertTrue(shellStyle.contains(".metric-nav"))
    }

    /**
     * 系统配置页只保留 WebUI 允许编辑的八个分类，避免日志和内部文件路径混入设置入口。
     */
    @Test
    fun `settings page should expose refined category tabs and hide internal settings fields`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        listOf(
            "integration" to "对接配置",
            "feature" to "功能开关",
            "bili" to "B站配置",
            "polling" to "轮询配置",
            "render" to "渲染配置",
            "message" to "消息配置",
            "admin" to "管理员",
            "translate" to "翻译配置",
        ).forEach { (key, label) ->
            assertTrue(shellPage.contains("""data-settings-tab="$key""""))
            assertTrue(shellPage.contains("""data-settings-panel="$key""""))
            assertTrue(shellPage.contains(label))
        }
        assertTrue(shellPage.contains("""aria-pressed="true""""))
        assertTrue(shellPage.contains("""class="settings-placeholder""""))
        assertTrue(shellScript.contains("settingsTabButtons"))
        assertTrue(shellScript.contains("activateSettingsTab"))
        assertTrue(shellStyle.contains(".settings-placeholder"))
        assertTrue(shellStyle.contains("max-width: none;"))
        assertTrue(shellStyle.contains("width: 50%;"))
        assertTrue(shellStyle.contains("justify-self: center;"))
        assertTrue(shellStyle.contains("align-self: start;"))
        assertTrue(shellScript.contains("renderSettingFieldWithUnit"))
        assertTrue(shellScript.contains("""unit: "小时""""))
        assertTrue(shellScript.contains("""unit: "秒""""))
        assertTrue(shellScript.contains("""unit: "毫秒""""))
        assertTrue(shellScript.contains("""unit: "天""""))
        assertTrue(shellScript.contains("""key: "platform.onebot11.heartbeatInterval", label: "心跳间隔", type: "number", unit: "毫秒""""))
        assertTrue(shellScript.contains("""key: "platform.onebot11.reconnectInterval", label: "重连间隔", type: "number", unit: "毫秒""""))
        assertTrue(shellScript.contains("""key: "platform.onebot11.connectTimeout", label: "连接超时", type: "number", unit: "毫秒""""))
        assertTrue(shellScript.contains("""{value: "base64", label: "base64"}"""))
        assertTrue(shellScript.contains("""{value: "file", label: "file"}"""))
        assertFalse(shellScript.contains("""label: "转为文本编码""""))
        assertFalse(shellScript.contains("""label: "文件路径""""))
        assertTrue(shellScript.contains("parseAdminLines"))
        assertTrue(shellScript.contains("validatePortValue"))
        assertTrue(shellScript.contains("validateHourRangeValue"))
        assertTrue(shellScript.contains("validateIntervalRangeValue"))
        assertTrue(shellScript.contains("validateGradientHexColorValue"))
        assertTrue(shellScript.contains("formatAdminSummary"))
        assertTrue(shellScript.contains("adminContactQQ"))
        assertFalse(shellPage.contains("""data-settings-tab="log""""))
        assertFalse(shellPage.contains("""data-settings-panel="log""""))
        assertFalse(shellPage.contains("日志配置"))
        assertFalse(shellScript.contains("renderLogSettings"))
        assertFalse(shellScript.contains("凭据文件"))
        assertFalse(shellScript.contains("外部静态目录"))
        assertFalse(shellScript.contains("动态模板表"))
        assertFalse(shellScript.contains("直播模板表"))
        assertFalse(shellScript.contains("下播模板表"))
        assertFalse(shellScript.contains("预置推送目标 JSON"))
        assertFalse(shellScript.contains("超级管理员联系人"))
        assertFalse(shellScript.contains("群普通管理员 JSON"))
        assertFalse(shellScript.contains("首次运行状态"))
        assertFalse(shellPage.contains("QQ 配置"))
        assertFalse(shellPage.contains("WebSocket 配置"))
        assertFalse(shellPage.contains("请输入 QQ 账号"))
        assertFalse(shellPage.contains("启用 WebSocket"))
    }

    /**
     * 主壳页的交互脚本和样式必须带资源版本，避免 hash 页面复用旧缓存后管理员按钮没有事件绑定。
     */
    @Test
    fun `frontend shell should version account control assets`() {
        val shellPage = read("src/main/resources/webui/index.html")

        assertTrue(shellPage.contains("""/assets/app.css?v=settings-editor-v1"""))
        assertTrue(shellPage.contains("""/assets/app.js?v=settings-editor-v1"""))
    }

    /**
     * 顶栏自身必须建立高层级上下文，避免 backdrop-filter 生成的层叠上下文被后续内容卡片盖住。
     */
    @Test
    fun `frontend shell should keep admin menu above dashboard cards`() {
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellStyle.contains(".topbar {"))
        assertTrue(shellStyle.contains("position: relative;"))
        assertTrue(shellStyle.contains("z-index: 60;"))
    }

    /**
     * 侧边栏宽度需要比早期窄版扩大约三分之一，避免用户侧导航和主题区显得过挤。
     */
    @Test
    fun `frontend shell should keep the wider sidebar layout`() {
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellStyle.contains("""--sidebar-width: 235px;"""))
        assertTrue(shellStyle.contains("@media"))
        assertTrue(shellStyle.contains("width: 213px;"))
        assertFalse(shellStyle.contains("""--sidebar-width: 176px;"""))
    }

    /**
     * 顶栏管理员入口必须暴露菜单、退出登录和居中改密弹窗，避免视觉按钮回退成不可交互文本。
     */
    @Test
    fun `frontend shell should expose usable admin account controls`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellPage.contains("""id="admin-menu-button""""))
        assertTrue(shellPage.contains("""id="admin-menu""""))
        assertTrue(shellPage.contains("""id="change-password-modal""""))
        assertTrue(shellPage.contains("""id="modal-current-password""""))
        assertTrue(shellPage.contains("""id="modal-new-password""""))
        assertTrue(shellPage.contains("""id="modal-confirm-password""""))
        assertTrue(shellPage.contains("退出登录"))
        assertTrue(shellPage.contains("修改密码"))
        assertTrue(shellScript.contains("/api/auth/logout"))
        assertTrue(shellScript.contains("/api/auth/change-password"))
        assertTrue(shellScript.contains("旧密码错误"))
        assertTrue(shellScript.contains("新密码和确认密码不一致"))
        assertTrue(shellStyle.contains(".admin-menu"))
        assertTrue(shellStyle.contains(".modal-backdrop"))
        assertTrue(shellStyle.contains(".password-modal"))
    }

    /**
     * 保存配置后的重启提示必须只由实际变化的重启字段触发，避免管理员等热生效字段误报。
     */
    @Test
    fun `settings save should warn only when changed fields require restart`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")
        val restartKeyBlock = Regex("""(?s)const restartRequiredSettingKeysByFile = \{(.*?)\};""")
            .find(shellScript)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        assertTrue(shellPage.contains("""id="restart-required-modal""""))
        assertTrue(shellPage.contains("""id="confirm-restart-required""""))
        assertFalse(shellPage.contains("""id="close-restart-required""""))
        assertTrue(shellScript.contains("restartRequiredSettingKeysByFile"))
        assertTrue(shellScript.contains("settingsPayloadHasRestartRequiredChanges"))
        assertTrue(shellScript.contains("previousRestartSettingValue"))
        assertTrue(shellScript.contains("normalizeRestartComparableValue"))
        assertTrue(shellScript.contains("showRestartRequiredModal"))
        assertTrue(shellScript.contains("restartRequiredChanged"))
        assertTrue(shellScript.contains("originalValue"))
        listOf(
            "platform.type",
            "platform.adapter",
            "platform.onebot11.host",
            "platform.onebot11.port",
            "platform.onebot11.token",
            "platform.onebot11.useTls",
            "platform.onebot11.heartbeatInterval",
            "platform.onebot11.reconnectInterval",
            "platform.onebot11.sendMode",
            "platform.onebot11.maxReconnectAttempts",
            "platform.onebot11.connectTimeout",
            "platform.qqOfficial.appId",
            "platform.qqOfficial.appSecret",
            "platform.qqOfficial.botToken",
            "webui.enabled",
            "webui.host",
            "webui.port",
            "webui.tokenTtlSeconds",
            "enableConfig.debugMode",
            "enableConfig.liveCloseNotifyEnable",
            "enableConfig.lowSpeedEnable",
            "enableConfig.cacheClearEnable",
            "accountConfig.cookie",
            "accountConfig.followGroup",
            "proxyConfig.proxy",
            "checkConfig.lowSpeedTime",
            "checkConfig.lowSpeedRange",
            "checkConfig.normalRange",
            "checkConfig.checkReportInterval",
            "checkConfig.timeout",
            "imageConfig.quality",
            "imageConfig.theme",
            "imageConfig.font",
            "cacheConfig.expires.DRAW",
            "cacheConfig.expires.IMAGES",
            "cacheConfig.expires.EMOJI",
            "cacheConfig.expires.USER",
            "cacheConfig.expires.OTHER",
            "pushConfig.toShortLink",
        ).forEach { key ->
            assertTrue(restartKeyBlock.contains(""""$key""""), key)
        }
        assertFalse(restartKeyBlock.contains(""""admins""""))
        assertFalse(restartKeyBlock.contains(""""adminContact""""))
        assertFalse(restartKeyBlock.contains(""""pushConfig.messageInterval""""))
        assertFalse(restartKeyBlock.contains(""""pushConfig.pushInterval""""))
        assertTrue(shellStyle.contains(".restart-required-modal"))
        assertTrue(shellStyle.contains(".restart-required-actions"))
        assertTrue(shellStyle.contains("justify-content: flex-end;"))
    }

    /**
     * 日志窗口需要自动轮询并保持可滚动尾部视图，避免只靠手动刷新才能看到新日志。
     */
    @Test
    fun `log list should stay scrollable within the static shell`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")

        assertTrue(shellPage.contains("""id="log-level-filter""""))
        assertTrue(shellPage.contains("""id="log-source-filter""""))
        assertTrue(shellPage.contains("""id="log-search-input""""))
        assertTrue(shellPage.contains("""id="log-auto-refresh""""))
        assertTrue(shellPage.contains("""id="log-clear-button""""))
        assertTrue(shellPage.contains("""id="log-export-button""""))
        assertTrue(shellPage.contains("""data-log-list"""))
        assertTrue(shellStyle.contains(".page-logs"))
        assertTrue(shellStyle.contains("height: 100%"))
        assertTrue(shellStyle.contains("flex-direction: column"))
        assertTrue(shellStyle.contains(".log-panel"))
        assertTrue(shellStyle.contains("flex: 1"))
        assertTrue(shellStyle.contains("min-height: 0"))
        assertTrue(shellStyle.contains(".log-list"))
        assertFalse(shellStyle.contains("max-height: 548px"))
        assertTrue(shellStyle.contains("overflow: auto"))
        assertTrue(shellStyle.contains("align-items: flex-end"))
        assertTrue(shellStyle.contains("align-self: end"))
        assertTrue(shellScript.contains("views.has(viewName)"))
        assertTrue(shellScript.contains("/api/logs/sources"))
        assertTrue(shellScript.contains("/api/logs/"))
        assertTrue(shellScript.contains("renderLogRows"))
        assertTrue(shellScript.contains("clearCurrentLog"))
        assertTrue(shellScript.contains("exportCurrentLog"))
    }

    /**
     * 订阅管理页必须从真实接口渲染，并让标签筛选和搜索框成为可交互控件。
     */
    @Test
    fun `subscription management should use interactive filters search and rendered cards`() {
        val shellPage = read("src/main/resources/webui/index.html")
        val shellScript = read("src/main/resources/webui/assets/app.js")
        val shellStyle = read("src/main/resources/webui/assets/app.css")
        val subscriptionGridBlock = cssBlock(shellStyle, ".subscription-grid")
        val primaryButtonBlock = cssBlock(shellStyle, ".btn-primary")
        val subscriptionActionsRowBlock = cssBlock(shellStyle, ".subscription-actions-row")

        assertTrue(shellPage.contains("""data-subscription-filter="all""""))
        assertTrue(shellPage.contains("""data-subscription-filter="dynamic" aria-pressed="false">订阅</button>"""))
        assertTrue(shellPage.contains("""data-subscription-filter="bangumi""""))
        assertTrue(shellPage.contains("""data-subscription-filter="group""""))
        assertFalse(shellPage.contains("直播与动态"))
        assertTrue(shellPage.contains("""id="subscription-modal""""))
        assertTrue(shellPage.contains("""id="subscription-edit-modal""""))
        assertTrue(shellPage.contains("""id="subscription-delete-modal""""))
        assertTrue(shellPage.contains("删除后该UID、番剧、分组下的所有信息将会丢失"))
        assertTrue(shellPage.contains("""data-edit-action="filter">编辑过滤器</button>"""))
        assertTrue(shellPage.contains("""data-edit-action="template">编辑模板</button>"""))
        assertTrue(shellPage.contains("""data-edit-action="atall">编辑at全体</button>"""))
        assertTrue(shellPage.contains("""data-edit-action="theme">编辑主题色</button>"""))
        assertTrue(shellScript.contains("""<span>目标群聊</span>"""))
        assertTrue(shellScript.contains("添加过滤器"))
        assertTrue(shellScript.contains("添加模板"))
        assertTrue(shellScript.contains("添加at全体"))
        assertTrue(shellScript.contains("开启随机模板"))
        assertTrue(shellScript.contains("templateExplainText"))
        assertTrue(shellScript.contains("targetGroups"))
        assertTrue(shellScript.contains("""data-multi-select="targetGroups""""))
        assertFalse(shellScript.contains("""<select name="targetGroups" multiple"""))
        assertTrue(shellScript.contains("暂无过滤器"))
        assertTrue(shellScript.contains("暂无模板"))
        assertTrue(shellScript.contains("暂无atall信息"))
        assertFalse(shellPage.contains("修改主题色"))
        assertTrue(shellPage.contains("""data-add-subscription-open"""))
        assertTrue(shellPage.contains("""id="subscription-search-input""""))
        assertTrue(shellPage.contains("""data-subscription-list"""))
        assertTrue(shellScript.contains("/api/subscriptions"))
        assertTrue(shellScript.contains("createSubscription"))
        assertTrue(shellScript.contains("deleteSubscription"))
        assertTrue(shellScript.contains("openSubscriptionDeleteModal"))
        assertTrue(shellScript.contains("confirmSubscriptionDelete"))
        assertTrue(shellScript.contains("openSubscriptionEditModal"))
        assertTrue(shellScript.contains("""data-edit-action="filter""""))
        assertTrue(shellScript.contains("loadFilterEditor"))
        assertTrue(shellScript.contains("loadTemplateEditor"))
        assertTrue(shellScript.contains("loadAtAllEditor"))
        assertTrue(shellScript.contains("loadThemeEditor"))
        assertTrue(shellScript.contains("/templates/random"))
        assertTrue(shellScript.contains("HEX颜色格式错误"))
        assertTrue(shellScript.contains("目标群聊"))
        assertFalse(shellScript.contains("""window.confirm("确认删除这个订阅"""))
        assertTrue(shellScript.contains("renderSubscriptions"))
        assertTrue(shellScript.contains("filteredSubscriptions"))
        assertTrue(shellScript.contains("formatSubscriptionSubject"))
        assertTrue(shellScript.contains("""dynamic: "订阅""""))
        assertTrue(shellScript.contains("""normalizedTag === "订阅""""))
        assertFalse(shellScript.contains("直播与动态"))
        assertTrue(shellScript.contains("item.targetSectionTitle"))
        assertTrue(shellScript.contains("filterCount"))
        assertTrue(shellScript.contains("templateCount"))
        assertTrue(shellScript.contains("themeColorCount"))
        assertTrue(shellStyle.contains("grid-template-columns: repeat(4, 320px)"))
        assertTrue(subscriptionGridBlock.contains("align-content: start;"))
        assertTrue(subscriptionGridBlock.contains("align-items: start;"))
        assertTrue(shellStyle.contains("width: 320px"))
        assertTrue(shellStyle.contains(".subscription-info-grid"))
        assertTrue(shellStyle.contains(".page-subscriptions"))
        assertTrue(shellStyle.contains("min-height: calc(100vh - 96px);"))
        assertTrue(shellStyle.contains("flex: 1;"))
        assertTrue(shellStyle.contains("min-height: calc(100vh - 168px);"))
        assertTrue(shellStyle.contains("place-content: center;"))
        assertTrue(shellStyle.contains(".subscription-modal-card"))
        assertTrue(shellStyle.contains(".subscription-actions-row"))
        assertTrue(shellStyle.contains("--primary-action-shadow: 0 12px 24px rgba(45, 108, 246, 0.24);"))
        assertTrue(shellStyle.contains("--primary-action-shadow: 0 12px 24px rgba(4, 12, 28, 0.42);"))
        assertTrue(shellStyle.contains("box-shadow: var(--primary-action-shadow);"))
        assertTrue(primaryButtonBlock.contains("border: 0;"))
        assertTrue(shellStyle.contains("aspect-ratio: 1 / 1;"))
        assertTrue(shellStyle.contains(".subscription-config-footer-left"))
        assertTrue(subscriptionActionsRowBlock.contains("padding: 10px 20px 20px;"))
        assertFalse(subscriptionGridBlock.contains("justify-self: center;"))
        assertFalse(shellPage.contains("全部 (56)"))
        assertFalse(shellPage.contains("icon-more-vertical\"></use></svg>\n                        </div>\n                        <div class=\"subscription-meta-title\">推送目标</div>"))
    }
}
