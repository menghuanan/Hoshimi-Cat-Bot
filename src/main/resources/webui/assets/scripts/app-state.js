/**
 * WebUI shared state keeps DOM references and mutable page state in plain-script scope for the conservative asset split.
 */

const pageTitle = document.getElementById("page-title");
const contentArea = document.querySelector(".content");
const navItems = Array.from(document.querySelectorAll("[data-nav-target]"));
const metricNavButtons = Array.from(document.querySelectorAll("[data-metric-nav]"));
const views = new Map(
    Array.from(document.querySelectorAll("[data-view]"), (view) => [view.dataset.view, view]),
);

const viewTitles = {
    home: "首页",
    settings: "系统配置",
    subscriptions: "订阅管理",
    logs: "日志",
};

const defaultView = "home";
const runtimeFields = new Map(
    Array.from(document.querySelectorAll("[data-runtime-field]"), (field) => [field.dataset.runtimeField, field]),
);
const runtimeProgressBars = new Map(
    Array.from(document.querySelectorAll("[data-runtime-progress]"), (bar) => [bar.dataset.runtimeProgress, bar]),
);
const runtimeLists = new Map(
    Array.from(document.querySelectorAll("[data-runtime-list]"), (list) => [list.dataset.runtimeList, list]),
);
const themePreferenceButtons = Array.from(document.querySelectorAll("[data-theme-option]"));
const themePreferenceLabel = document.querySelector("[data-theme-label]");
const adminMenuButton = document.getElementById("admin-menu-button");
const adminMenu = document.getElementById("admin-menu");
const openChangePasswordButton = document.getElementById("open-change-password");
const logoutButton = document.getElementById("logout-button");
const changePasswordModal = document.getElementById("change-password-modal");
const closeChangePasswordButton = document.getElementById("close-change-password");
const cancelChangePasswordButton = document.getElementById("cancel-change-password");
const modalChangePasswordForm = document.getElementById("modal-change-password-form");
const modalCurrentPasswordInput = document.getElementById("modal-current-password");
const modalNewPasswordInput = document.getElementById("modal-new-password");
const modalConfirmPasswordInput = document.getElementById("modal-confirm-password");
const modalPasswordStatus = document.getElementById("modal-password-status");
const restartRequiredModal = document.getElementById("restart-required-modal");
const confirmRestartRequiredButton = document.getElementById("confirm-restart-required");
const highRiskConfirmModal = document.getElementById("high-risk-confirm-modal");
const highRiskConfirmTitle = document.getElementById("high-risk-confirm-title");
const highRiskConfirmMessage = document.getElementById("high-risk-confirm-message");
const highRiskConfirmPasswordField = document.getElementById("high-risk-confirm-password-field");
const highRiskConfirmPasswordInput = document.getElementById("high-risk-confirm-password");
const highRiskConfirmStatus = document.getElementById("high-risk-confirm-status");
const closeHighRiskConfirmButton = document.getElementById("close-high-risk-confirm");
const cancelHighRiskConfirmButton = document.getElementById("cancel-high-risk-confirm");
const submitHighRiskConfirmButton = document.getElementById("submit-high-risk-confirm");
const logSourceFilter = document.getElementById("log-source-filter");
const logLevelFilter = document.getElementById("log-level-filter");
const logModuleFilter = document.getElementById("log-module-filter");
const logSearchInput = document.getElementById("log-search-input");
const logAutoRefresh = document.getElementById("log-auto-refresh");
const logClearButton = document.getElementById("log-clear-button");
const logExportButton = document.getElementById("log-export-button");
const logStatus = document.getElementById("log-status");
const logList = document.querySelector("[data-log-list]");
const subscriptionFilterButtons = Array.from(document.querySelectorAll("[data-subscription-filter]"));
const subscriptionSearchInput = document.getElementById("subscription-search-input");
const subscriptionList = document.querySelector("[data-subscription-list]");
const addSubscriptionButton = document.querySelector("[data-add-subscription-open]");
const subscriptionModal = document.getElementById("subscription-modal");
const closeSubscriptionModalButton = document.getElementById("close-subscription-modal");
const cancelSubscriptionModalButton = document.getElementById("cancel-subscription-modal");
const subscriptionCreateForm = document.getElementById("subscription-create-form");
const subscriptionCreateType = document.getElementById("subscription-create-type");
const subscriptionModalStatus = document.getElementById("subscription-modal-status");
const subscriptionEditModal = document.getElementById("subscription-edit-modal");
const closeSubscriptionEditButton = document.getElementById("close-subscription-edit");
const subscriptionEditStatus = document.getElementById("subscription-edit-status");
const subscriptionEditTitle = document.getElementById("subscription-edit-title");
const subscriptionEditActionPanel = subscriptionEditModal?.querySelector(".subscription-actions-row");
const subscriptionDeleteModal = document.getElementById("subscription-delete-modal");
const closeSubscriptionDeleteButton = document.getElementById("close-subscription-delete");
const cancelSubscriptionDeleteButton = document.getElementById("cancel-subscription-delete");
const confirmSubscriptionDeleteButton = document.getElementById("confirm-subscription-delete");
const subscriptionDeleteSummary = document.getElementById("subscription-delete-summary");
const subscriptionDeleteStatus = document.getElementById("subscription-delete-status");
const settingsTabButtons = Array.from(document.querySelectorAll("[data-settings-tab]"));
const settingsPanels = Array.from(document.querySelectorAll("[data-settings-panel]"));
const themePreferenceCookieName = window.WebUiTheme?.cookieName || "dynamic_bot_webui_theme";
const runtimeRefreshIntervalMs = 30_000;
const logRefreshIntervalMs = 5_000;
const logTailLines = 500;
const logLinePattern = /^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\s+(?:\[[^\]]+\]\s+)?(TRACE|DEBUG|INFO|WARN|ERROR)\s+(.+?)\s+-\s+(.*)$/;
const logState = {
    sourcesLoaded: false,
    sourceId: "",
    rows: [],
    modules: [],
    renderedRows: [],
};
const subscriptionState = {
    filter: "all",
    search: "",
    items: [],
    loaded: false,
    editingItemId: "",
    editingAction: "",
    editingLists: {
        filter: [],
        template: [],
        atall: [],
    },
    pendingDeleteItemId: "",
};
const settingsState = {
    loaded: false,
    activeTab: "integration",
    files: {
        biliConfig: {sourceFile: "BiliConfig.yml", snapshotToken: "", fieldsByKey: new Map()},
        botConfig: {sourceFile: "bot.yml", snapshotToken: "", fieldsByKey: new Map()},
    },
    status: "",
};
const highRiskConfirmationState = {
    resolve: null,
    mode: "confirm",
};
let logRefreshTimer = null;
let logRequestSequence = 0;
const templateExplainText = {
    dynamic: [
        "动态模板可用占位符",
        "{name} UP主名称　{uid}/{mid} 用户ID　{time} 发布时间",
        "{type} 动态类型　{did} 动态ID　{content} 正文摘要",
        "{link} 动态链接　{links} 动态内链接列表",
        "{draw} 推送卡片图　{images} 动态图片",
        "使用 \\r 可把模板拆成多条消息。",
    ].join("\n"),
    live: [
        "开播模板可用占位符",
        "{name} 主播名称　{uid}/{mid} 用户ID　{time} 开播时间",
        "{title} 直播标题　{area} 直播分区",
        "{link} 直播间链接　{cover} 直播封面",
        "{draw} 推送卡片图",
        "使用 \\r 可把模板拆成多条消息。",
    ].join("\n"),
    liveClose: [
        "下播模板可用占位符",
        "{name} 主播名称　{uid}/{mid} 用户ID　{time} 下播时间",
        "{title} 直播标题　{duration} 直播时长",
        "{area} 直播分区　{link} 直播间链接",
        "使用 \\r 可把模板拆成多条消息。",
    ].join("\n"),
};
const restartRequiredSettingKeysByFile = {
    biliConfig: new Set([
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
    ]),
    botConfig: new Set([
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
    ]),
};
const restartRequiredPayloadKeyByFile = {
    biliConfig: {
        debugMode: "enableConfig.debugMode",
        liveCloseNotifyEnable: "enableConfig.liveCloseNotifyEnable",
        lowSpeedEnable: "enableConfig.lowSpeedEnable",
        cacheClearEnable: "enableConfig.cacheClearEnable",
        cookie: "accountConfig.cookie",
        followGroup: "accountConfig.followGroup",
        proxies: "proxyConfig.proxy",
        lowSpeedTime: "checkConfig.lowSpeedTime",
        lowSpeedRange: "checkConfig.lowSpeedRange",
        normalRange: "checkConfig.normalRange",
        checkReportInterval: "checkConfig.checkReportInterval",
        timeout: "checkConfig.timeout",
        quality: "imageConfig.quality",
        theme: "imageConfig.theme",
        font: "imageConfig.font",
        "cacheExpires.DRAW": "cacheConfig.expires.DRAW",
        "cacheExpires.IMAGES": "cacheConfig.expires.IMAGES",
        "cacheExpires.EMOJI": "cacheConfig.expires.EMOJI",
        "cacheExpires.USER": "cacheConfig.expires.USER",
        "cacheExpires.OTHER": "cacheConfig.expires.OTHER",
        toShortLink: "pushConfig.toShortLink",
    },
    botConfig: {
        platformType: "platform.type",
        adapter: "platform.adapter",
        oneBot11Host: "platform.onebot11.host",
        oneBot11Port: "platform.onebot11.port",
        oneBot11Token: "platform.onebot11.token",
        oneBot11UseTls: "platform.onebot11.useTls",
        oneBot11HeartbeatInterval: "platform.onebot11.heartbeatInterval",
        oneBot11ReconnectInterval: "platform.onebot11.reconnectInterval",
        oneBot11SendMode: "platform.onebot11.sendMode",
        oneBot11MaxReconnectAttempts: "platform.onebot11.maxReconnectAttempts",
        oneBot11ConnectTimeout: "platform.onebot11.connectTimeout",
        qqOfficialAppId: "platform.qqOfficial.appId",
        qqOfficialAppSecret: "platform.qqOfficial.appSecret",
        qqOfficialBotToken: "platform.qqOfficial.botToken",
        webUiEnabled: "webui.enabled",
        webUiHost: "webui.host",
        webUiPort: "webui.port",
        webUiTokenTtlSeconds: "webui.tokenTtlSeconds",
    },
};
const restartMaskedSettingKeys = new Set([
    "accountConfig.cookie",
    "proxyConfig.proxy",
    "platform.onebot11.token",
    "platform.qqOfficial.appSecret",
    "platform.qqOfficial.botToken",
]);

