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
    "platform.onebot11.token",
    "platform.qqOfficial.appSecret",
    "platform.qqOfficial.botToken",
]);

/**
 * 读取当前 WebUI token；没有 sessionStorage 时仍允许同源 cookie 完成认证。
 */
function getWebUiToken() {
    return sessionStorage.getItem("webuiToken") || "";
}

/**
 * 认证请求统一附带 Bearer token 和 JSON 协商头，便于登出、改密和运行态刷新保持同一认证来源。
 */
function buildAuthHeaders(includeJson = false) {
    const headers = {
        Accept: "application/json",
    };
    const token = getWebUiToken();
    if (token) {
        headers.Authorization = `Bearer ${token}`;
    }
    if (includeJson) {
        headers["Content-Type"] = "application/json";
    }
    return headers;
}

/**
 * 首页状态字段统一写入，避免某个 DOM 节点缺失时中断整次刷新。
 */
function setRuntimeField(name, value) {
    const field = runtimeFields.get(name);
    if (field) {
        field.textContent = value;
    }
}

/**
 * 首页进度条只接受 0-100 的安全百分比，避免异常后端值撑坏布局。
 */
function setRuntimeProgress(name, value) {
    const bar = runtimeProgressBars.get(name);
    if (!bar) {
        return;
    }
    const percent = Number(value);
    if (!Number.isFinite(percent)) {
        bar.style.width = "0%";
        return;
    }
    bar.style.width = `${Math.min(100, Math.max(0, percent))}%`;
}

/**
 * 列表区域统一通过整段 HTML 重绘，避免局部更新后残留旧行导致首页状态不一致。
 */
function setRuntimeList(name, html) {
    const list = runtimeLists.get(name);
    if (list) {
        list.innerHTML = html;
    }
}

/**
 * 主题偏好只接受深色、亮色和跟随系统三种值，避免 cookie 污染后把页面切成未知状态。
 */
function resolveThemePreference(preference) {
    if (preference === "dark" || preference === "light" || preference === "system") {
        return preference;
    }
    return "system";
}

/**
 * 主题按钮只负责写入全局主题助手，再把 footer 的选中态同步回来，避免局部状态漂移。
 */
function applyThemePreference(preference) {
    const normalizedPreference = resolveThemePreference(preference);
    if (window.WebUiTheme) {
        window.WebUiTheme.setPreference(normalizedPreference);
    }
    syncThemePreferenceUI(normalizedPreference);
    return normalizedPreference;
}

/**
 * 左下角主题切换器和 cookie 保持同一个偏好值，刷新后无需重新点选。
 */
function syncThemePreferenceUI(preference) {
    const normalizedPreference = resolveThemePreference(preference);
    themePreferenceButtons.forEach((button) => {
        const active = button.dataset.themeOption === normalizedPreference;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", String(active));
    });
    if (themePreferenceLabel && window.WebUiTheme?.getPreferenceLabel) {
        themePreferenceLabel.textContent = window.WebUiTheme.getPreferenceLabel(normalizedPreference);
    } else if (themePreferenceLabel) {
        themePreferenceLabel.textContent = normalizedPreference === "dark"
            ? "深色"
            : normalizedPreference === "light"
                ? "亮色"
                : "跟随系统";
    }
}

/**
 * 将后端生命周期枚举转换为 operator 可直接扫读的中文状态。
 */
function formatLifecycleState(state) {
    const states = {
        STARTING: "启动中",
        RUNNING: "运行中",
        STOPPING: "停止中",
        STOPPED: "已停止",
    };
    return states[state] || "未知";
}

/**
 * 将秒级运行时长压缩为首页状态卡可容纳的短文案。
 */
function formatUptime(seconds) {
    const safeSeconds = Math.max(0, Number(seconds) || 0);
    const days = Math.floor(safeSeconds / 86400);
    const hours = Math.floor((safeSeconds % 86400) / 3600);
    const minutes = Math.floor((safeSeconds % 3600) / 60);
    if (days > 0) {
        return `已运行 ${days} 天 ${hours} 小时 ${minutes} 分钟`;
    }
    if (hours > 0) {
        return `已运行 ${hours} 小时 ${minutes} 分钟`;
    }
    return `已运行 ${minutes} 分钟`;
}

/**
 * 将后端毫秒时间戳格式化为首页紧凑时间；缺失时保留占位符。
 */
function formatDateTime(epochMillis) {
    const value = Number(epochMillis);
    if (!Number.isFinite(value) || value <= 0) {
        return "--";
    }
    return new Date(value).toLocaleString("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
    });
}

/**
 * 最近推送记录只显示时分秒，和首页卡片里的短时间格式保持一致。
 */
function formatTimeOnly(epochMillis) {
    const value = Number(epochMillis);
    if (!Number.isFinite(value) || value <= 0) {
        return "--";
    }
    return new Date(value).toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false,
    });
}

/**
 * 首页列表内容来自后端摘要，渲染前先做 HTML 转义，避免消息文本破坏静态壳结构。
 */
function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => {
        const map = {
            "&": "&amp;",
            "<": "&lt;",
            ">": "&gt;",
            '"': "&quot;",
            "'": "&#39;",
        };
        return map[char] || char;
    });
}

/**
 * 订阅头像从名称首字符生成，缺少名称时使用默认占位，避免接口不返回头像也能稳定排版。
 */
function subscriptionAvatarText(item) {
    const title = String(item?.title || "").trim();
    return (title ? Array.from(title)[0] : "?").toUpperCase();
}

/**
 * 订阅头像色按卡片位置循环使用既有渐变类，保持和原静态页面一致的视觉节奏。
 */
function subscriptionAvatarClass(index) {
    const classes = ["avatar-a", "avatar-b", "avatar-c", "avatar-d", "avatar-e", "avatar-f"];
    return classes[index % classes.length];
}

/**
 * 订阅标签复用现有 pill 色板，订阅、番剧和分组分别落到固定颜色。
 */
function subscriptionTagClass(tag) {
    const normalizedTag = String(tag || "");
    if (normalizedTag === "订阅") {
        return "pill-blue";
    }
    if (normalizedTag === "直播") {
        return "pill-gold";
    }
    if (normalizedTag === "动态") {
        return "pill-green";
    }
    if (normalizedTag === "番剧") {
        return "pill-purple";
    }
    return "pill-blue";
}

/**
 * 平台 subject 在卡片中转换为中文语义，避免直接暴露 group/private 等内部段名。
 */
function formatSubscriptionSubject(target) {
    const parts = String(target || "").split(":").filter(Boolean);
    if (parts.length >= 2 && parts[parts.length - 2] === "group") {
        return {type: "群聊", value: parts[parts.length - 1], raw: target};
    }
    if (parts.length >= 2 && parts[parts.length - 2] === "private") {
        return {type: "私聊", value: parts[parts.length - 1], raw: target};
    }
    return {type: "", value: target || "--", raw: target};
}

/**
 * 推送目标按联系人类型合并展示；分组卡片的订阅 UID 则直接展示 UID 列表。
 */
function renderSubscriptionTargets(item) {
    const targets = item?.targets;
    const items = Array.isArray(targets) ? targets : [];
    if (items.length === 0) {
        return '<span class="mini-chip mini-chip--muted">暂无目标</span>';
    }
    const grouped = new Map();
    items.forEach((target) => {
        const formatted = formatSubscriptionSubject(target);
        const key = formatted.type || "目标";
        const bucket = grouped.get(key) || [];
        bucket.push(formatted.value);
        grouped.set(key, bucket);
    });
    return Array.from(grouped.entries()).map(([type, values]) => {
        const text = type === "目标" ? values.join("、") : `${type}：${values.join("、")}`;
        return `<span class="mini-chip" title="${escapeHtml(text)}">${escapeHtml(text)}</span>`;
    }).join("");
}

/**
 * 信息块值限制为短文本；空模板和空主题色统一降级为默认文案。
 */
function subscriptionInfoText(value, fallback = "未设置") {
    if (Array.isArray(value)) {
        return value.length > 0 ? value.join("、") : fallback;
    }
    const text = String(value || "").trim();
    return text || fallback;
}

/**
 * 数量摘要统一带单位，避免信息块里混用原始配置片段和统计值。
 */
function formatSubscriptionCount(value, unit) {
    const count = Number(value);
    return `${Number.isFinite(count) && count > 0 ? count : 0} 个${unit}`;
}

/**
 * 最后更新时间使用后端聚合出的卡片管理信息更新时间，缺失时显示暂无更新。
 */
function formatSubscriptionUpdatedTime(epochMillis) {
    const value = Number(epochMillis);
    if (!Number.isFinite(value) || value <= 0) {
        return "最后更新：暂无更新";
    }
    return `最后更新：${formatDateTime(value)}`;
}

/**
 * 当前筛选同时应用标签按钮和搜索框，搜索范围覆盖名称、UID、目标和信息块文本。
 */
function filteredSubscriptions() {
    const keyword = subscriptionState.search.trim().toLowerCase();
    return subscriptionState.items.filter((item) => {
        const matchesFilter = subscriptionState.filter === "all" || item.kind === subscriptionState.filter;
        const searchable = [
            item.title,
            item.identifierLabel,
            item.sourceId,
            ...(item.tags || []),
            ...(item.targets || []),
            item.filterInfo,
            item.filterCount,
            ...(item.templateNames || []),
            item.templateCount,
            item.atAllInfo,
            item.themeColor,
            item.themeColorCount,
            item.targetSectionTitle,
        ].join(" ").toLowerCase();
        return matchesFilter && (!keyword || searchable.includes(keyword));
    });
}

/**
 * 顶部标签按钮只表达当前筛选态，文案保持需求中的三个固定分类。
 */
function syncSubscriptionFilters() {
    const labels = {
        all: "全部",
        dynamic: "订阅",
        bangumi: "番剧",
        group: "分组",
    };
    subscriptionFilterButtons.forEach((button) => {
        const filter = button.dataset.subscriptionFilter || "all";
        const active = filter === subscriptionState.filter;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", String(active));
        button.textContent = labels[filter] || labels.all;
    });
}

/**
 * 订阅卡片整段重绘，确保筛选、搜索和接口刷新不会残留旧卡片状态。
 */
function renderSubscriptions(summary = null) {
    if (!subscriptionList) {
        return;
    }
    syncSubscriptionFilters();
    const rows = filteredSubscriptions();
    if (rows.length === 0) {
        subscriptionList.innerHTML = '<div class="subscription-empty">没有匹配的订阅</div>';
        return;
    }
    subscriptionList.innerHTML = rows.map((item, index) => {
        const tags = (item.tags || []).map((tag) => {
            return `<span class="pill ${subscriptionTagClass(tag)}">${escapeHtml(tag)}</span>`;
        }).join("");
        return `
            <article class="subscription-card" data-subscription-id="${escapeHtml(item.id)}">
                <div class="subscription-head">
                    <div class="subscription-profile">
                        <span class="subscription-avatar ${subscriptionAvatarClass(index)}">${escapeHtml(subscriptionAvatarText(item))}</span>
                        <div>
                            <div class="subscription-name-row">
                                <h3 title="${escapeHtml(item.title)}">${escapeHtml(item.title || "--")}</h3>
                                ${tags}
                            </div>
                            <div class="subscription-uid">${escapeHtml(item.identifierLabel || `UID: ${item.sourceId ?? "--"}`)}</div>
                        </div>
                    </div>
                </div>
                <div class="subscription-meta-title">${escapeHtml(item.targetSectionTitle || "推送目标")}</div>
                <div class="chip-row">${renderSubscriptionTargets(item)}</div>
                <div class="subscription-info-grid">
                    <div class="subscription-info-block">
                        <span class="subscription-info-label">过滤器信息</span>
                        <span class="subscription-info-value" title="${escapeHtml(item.filterInfo)}"><span>${escapeHtml(formatSubscriptionCount(item.filterCount, "过滤器"))}</span></span>
                    </div>
                    <div class="subscription-info-block">
                        <span class="subscription-info-label">模板信息</span>
                        <span class="subscription-info-value" title="${escapeHtml(subscriptionInfoText(item.templateNames))}"><span>${escapeHtml(formatSubscriptionCount(item.templateCount, "模板"))}</span></span>
                    </div>
                    <div class="subscription-info-block">
                        <span class="subscription-info-label">at全体</span>
                        <span class="subscription-info-value" title="${escapeHtml(item.atAllInfo)}"><span>${escapeHtml(subscriptionInfoText(item.atAllInfo, "未开启"))}</span></span>
                    </div>
                    <div class="subscription-info-block">
                        <span class="subscription-info-label">主题色</span>
                        <span class="subscription-info-value" title="${escapeHtml(subscriptionInfoText(item.themeColor, "默认"))}"><span>${escapeHtml(formatSubscriptionCount(item.themeColorCount, "主题色"))}</span></span>
                    </div>
                </div>
                <div class="subscription-footer">
                    <span class="subscription-time">${escapeHtml(formatSubscriptionUpdatedTime(item.lastUpdatedEpochMillis))}</span>
                    <div class="subscription-card-actions">
                        <button class="btn btn-secondary btn-small subscription-edit" type="button" data-subscription-edit="${escapeHtml(item.id)}">编辑</button>
                        <button class="btn btn-secondary btn-small subscription-delete" type="button" data-subscription-delete="${escapeHtml(item.id)}">删除</button>
                    </div>
                </div>
            </article>
        `;
    }).join("");
}

/**
 * 添加订阅弹窗每次打开都重置状态，避免上次失败文案干扰新的提交。
 */
function openSubscriptionModal() {
    if (!subscriptionModal || !subscriptionCreateForm) {
        return;
    }
    subscriptionCreateForm.reset();
    updateSubscriptionCreateFields();
    setSubscriptionModalStatus("");
    subscriptionModal.hidden = false;
    subscriptionCreateType?.focus();
}

/**
 * 关闭添加订阅弹窗时只隐藏浮层，不改变当前列表筛选和搜索条件。
 */
function closeSubscriptionModal() {
    if (subscriptionModal) {
        subscriptionModal.hidden = true;
    }
}

/**
 * 添加类型决定表单字段组显隐，确保订阅、分组、番剧三类只提交各自需要的输入。
 */
function updateSubscriptionCreateFields() {
    const selected = subscriptionCreateType?.value || "dynamic";
    document.querySelectorAll("[data-subscription-fields]").forEach((group) => {
        group.hidden = group.dataset.subscriptionFields !== selected;
    });
}

/**
 * 添加订阅状态文案复用 modal-status 成功态，失败时保持默认红色。
 */
function setSubscriptionModalStatus(message, success = false) {
    if (!subscriptionModalStatus) {
        return;
    }
    subscriptionModalStatus.textContent = message || "";
    subscriptionModalStatus.classList.toggle("is-success", Boolean(success));
}

/**
 * 根据当前类型收集表单值，字段名保持与后端 DTO 一致。
 */
function buildSubscriptionCreatePayload() {
    const type = subscriptionCreateType?.value || "dynamic";
    if (type === "group") {
        return {
            type,
            groupName: document.getElementById("subscription-create-group-name")?.value || "",
            uid: document.getElementById("subscription-create-group-uid")?.value || "",
            targetGroup: document.getElementById("subscription-create-group-target")?.value || "",
        };
    }
    if (type === "bangumi") {
        return {
            type,
            bangumiId: document.getElementById("subscription-create-bangumi-id")?.value || "",
            targetGroup: document.getElementById("subscription-create-bangumi-target")?.value || "",
        };
    }
    return {
        type,
        uid: document.getElementById("subscription-create-uid")?.value || "",
        targetGroup: document.getElementById("subscription-create-target")?.value || "",
    };
}

/**
 * 新增订阅走后端业务 facade，成功后刷新卡片列表以展示真实写入结果。
 */
async function createSubscription() {
    const response = await fetch("/api/subscriptions", {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify(buildSubscriptionCreatePayload()),
    });
    if (response.status === 401 || response.status === 403) {
        location.href = "/login";
        return;
    }
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.success === false) {
        const message = payload.message || `添加失败：HTTP ${response.status}`;
        setSubscriptionModalStatus(message);
        return;
    }
    setSubscriptionModalStatus(payload.message || "添加成功", true);
    await refreshSubscriptions();
    closeSubscriptionModal();
}

/**
 * 删除订阅前打开页面内确认弹窗，避免浏览器默认 confirm 从顶部打断当前管理流。
 */
function openSubscriptionDeleteModal(itemId) {
    const item = subscriptionState.items.find((candidate) => candidate.id === itemId);
    if (!subscriptionDeleteModal || !item) {
        return;
    }
    subscriptionState.pendingDeleteItemId = itemId;
    if (subscriptionDeleteSummary) {
        subscriptionDeleteSummary.textContent = `确认删除 ${item.title || item.identifierLabel || itemId} 吗？`;
    }
    if (subscriptionDeleteStatus) {
        subscriptionDeleteStatus.textContent = "";
        subscriptionDeleteStatus.classList.remove("is-success");
    }
    subscriptionDeleteModal.hidden = false;
}

/**
 * 关闭删除确认弹窗时清空待删除 ID，防止取消后再次点击确认误删旧卡片。
 */
function closeSubscriptionDeleteModal() {
    subscriptionState.pendingDeleteItemId = "";
    if (subscriptionDeleteModal) {
        subscriptionDeleteModal.hidden = true;
    }
}

/**
 * 删除确认按钮只读取弹窗保存的待删除 ID，确保用户看见后果说明后才发出删除请求。
 */
async function confirmSubscriptionDelete() {
    const itemId = subscriptionState.pendingDeleteItemId;
    if (!itemId) {
        return;
    }
    await deleteSubscription(itemId);
}

/**
 * 删除订阅请求只负责调用后端和刷新列表，交互确认由页面弹窗提前完成。
 */
async function deleteSubscription(itemId) {
    const response = await fetch(`/api/subscriptions/${encodeURIComponent(itemId)}`, {
        method: "DELETE",
        headers: buildAuthHeaders(),
    });
    if (response.status === 401 || response.status === 403) {
        location.href = "/login";
        return;
    }
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.success === false) {
        setSubscriptionError(payload.message || `删除失败：HTTP ${response.status}`);
        return;
    }
    await refreshSubscriptions();
    closeSubscriptionDeleteModal();
}

/**
 * 编辑弹窗打开时先回到动作菜单，后续四类配置页都在同一窗口内切换。
 */
function openSubscriptionEditModal(itemId) {
    if (!subscriptionEditModal) {
        return;
    }
    subscriptionState.editingItemId = itemId || "";
    subscriptionState.editingAction = "";
    setSubscriptionEditStatus("");
    renderSubscriptionEditMenu();
    subscriptionEditModal.hidden = false;
}

/**
 * 关闭编辑弹窗时清空当前 itemId 和列表快照，避免后续动作误用上一次卡片。
 */
function closeSubscriptionEditModal() {
    subscriptionState.editingItemId = "";
    subscriptionState.editingAction = "";
    subscriptionState.editingLists = {filter: [], template: [], atall: []};
    setSubscriptionEditStatus("");
    if (subscriptionEditModal) {
        subscriptionEditModal.hidden = true;
    }
}

/**
 * 编辑弹窗底部状态统一处理成功和失败颜色，避免不同配置页各自拼接反馈样式。
 */
function setSubscriptionEditStatus(message, success = false) {
    if (!subscriptionEditStatus) {
        return;
    }
    subscriptionEditStatus.textContent = message || "";
    subscriptionEditStatus.classList.toggle("is-success", Boolean(success));
}

/**
 * 编辑弹窗状态条会被移动到当前页脚里，和右侧按钮始终保持同一行。
 */
function attachSubscriptionEditStatus() {
    if (!subscriptionEditStatus || !subscriptionEditActionPanel) {
        return;
    }
    const footer = subscriptionEditActionPanel.querySelector("[data-config-footer]");
    if (footer && subscriptionEditStatus.parentElement !== footer) {
        footer.prepend(subscriptionEditStatus);
    }
}

/**
 * 编辑弹窗标题统一由页面状态控制，方便列表页和表单页切换时保持清晰上下文。
 */
function setSubscriptionEditTitle(title) {
    if (subscriptionEditTitle) {
        subscriptionEditTitle.textContent = title || "编辑订阅";
    }
}

/**
 * 编辑菜单保留四个入口，并由后续页面负责加载真实后端配置。
 */
function renderSubscriptionEditMenu() {
    if (!subscriptionEditActionPanel) {
        return;
    }
    setSubscriptionEditTitle("编辑订阅");
    subscriptionEditActionPanel.innerHTML = `
        <button class="btn btn-secondary" type="button" data-edit-action="filter">编辑过滤器</button>
        <button class="btn btn-secondary" type="button" data-edit-action="template">编辑模板</button>
        <button class="btn btn-secondary" type="button" data-edit-action="atall">编辑at全体</button>
        <button class="btn btn-secondary" type="button" data-edit-action="theme">编辑主题色</button>
    `;
}

/**
 * 当前订阅配置 API 的基础路径统一编码 itemId，避免冒号或模板 key 破坏路由。
 */
function subscriptionConfigBaseUrl(suffix) {
    return `/api/subscriptions/${encodeURIComponent(subscriptionState.editingItemId)}${suffix}`;
}

/**
 * 配置接口统一解析 JSON 错误响应，让表单页可以直接显示后端校验文案。
 */
async function fetchSubscriptionConfigJson(url, options = {}) {
    const response = await fetch(url, options);
    if (response.status === 401 || response.status === 403) {
        location.href = "/login";
        return null;
    }
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || payload.success === false) {
        throw new Error(payload.message || `请求失败：HTTP ${response.status}`);
    }
    return payload;
}

/**
 * 列表页空状态使用同一块居中区域，满足没有过滤器、模板或 atall 时的展示要求。
 */
function emptyConfigList(text) {
    return `<div class="subscription-config-empty">${escapeHtml(text)}</div>`;
}

/**
 * 配置页加载态先渲染页脚，再写入状态文本，确保加载失败时提示也和按钮保持同一行。
 */
function renderConfigLoading(title, message) {
    setSubscriptionEditTitle(title);
    subscriptionEditActionPanel.innerHTML = `
        ${emptyConfigList("正在加载")}
        <div class="modal-actions subscription-config-footer" data-config-footer>
            <button class="btn btn-secondary" type="button" data-editor-back>取消</button>
        </div>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus(message || "正在加载");
}

/**
 * 过滤器列表按后端返回的 t/r 前缀逐行展示，并给每行保留编辑和删除按钮。
 */
async function loadFilterEditor() {
    subscriptionState.editingAction = "filter";
    renderConfigLoading("已有过滤器", "正在加载过滤器");
    const payload = await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/filters"), {headers: buildAuthHeaders()});
    const filters = Array.isArray(payload?.filters) ? payload.filters : [];
    subscriptionState.editingLists.filter = filters;
    const rows = filters.length === 0 ? emptyConfigList("暂无过滤器") : filters.map((filter) => `
        <div class="subscription-config-row">
            <span class="subscription-config-main" title="${escapeHtml(filter.scope)}">${escapeHtml(filter.prefix)} ${escapeHtml(filter.label)}：${escapeHtml(filter.content)}</span>
            <span class="subscription-config-actions">
                <button class="btn btn-secondary btn-small" type="button" data-config-edit="filter" data-config-key="${escapeHtml(filter.key)}">编辑</button>
                <button class="btn btn-secondary btn-small subscription-delete" type="button" data-config-delete="filter" data-config-key="${escapeHtml(filter.key)}">删除</button>
            </span>
        </div>
    `).join("");
    subscriptionEditActionPanel.innerHTML = `
        <div class="subscription-config-list">${rows}</div>
        <div class="modal-actions subscription-config-footer" data-config-footer>
            <button class="btn btn-secondary" type="button" data-editor-back>取消</button>
            <button class="btn btn-primary" type="button" data-config-add="filter">添加过滤器</button>
        </div>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus("");
}

/**
 * 过滤器表单根据正则或标签过滤切换输入项，编辑时用已有规则内容回填。
 */
function renderFilterForm(filter = null) {
    const kind = filter?.kind === "type" ? "type" : "regex";
    const mode = filter?.mode === "白名单" ? "white" : "black";
    setSubscriptionEditTitle(filter ? "编辑过滤器" : "添加过滤器");
    subscriptionEditActionPanel.innerHTML = `
        <form class="subscription-config-form" data-config-form="filter">
            <input type="hidden" name="key" value="${escapeHtml(filter?.key || "")}">
            <label class="field">
                <span>选择过滤方式</span>
                <select name="kind" data-filter-kind>
                    <option value="regex"${kind === "regex" ? " selected" : ""}>正则</option>
                    <option value="type"${kind === "type" ? " selected" : ""}>标签</option>
                </select>
            </label>
            <label class="field" data-filter-regex-field${kind === "type" ? " hidden" : ""}>
                <span>正则内容</span>
                <input name="regexContent" type="text" autocomplete="off" value="${escapeHtml(kind === "regex" ? filter?.content || "" : "")}">
            </label>
            <label class="field" data-filter-type-field${kind === "regex" ? " hidden" : ""}>
                <span>标签</span>
                <select name="typeContent">
                    ${["动态", "转发动态", "视频", "音乐", "专栏", "直播"].map((label) => `<option value="${label}"${filter?.content === label ? " selected" : ""}>${label}</option>`).join("")}
                </select>
            </label>
            <label class="field">
                <span>黑/白名单</span>
                <select name="mode">
                    <option value="black"${mode === "black" ? " selected" : ""}>黑名单</option>
                    <option value="white"${mode === "white" ? " selected" : ""}>白名单</option>
                </select>
            </label>
            <div class="modal-actions subscription-config-footer" data-config-footer>
                <button class="btn btn-secondary" type="button" data-config-cancel="filter">取消</button>
                <button class="btn btn-primary" type="submit">确认</button>
            </div>
        </form>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus("");
}

/**
 * 模板列表展示策略内已有模板，并把随机模板开关直接对接后端策略。
 */
async function loadTemplateEditor() {
    subscriptionState.editingAction = "template";
    renderConfigLoading("已有模板", "正在加载模板");
    const payload = await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/templates"), {headers: buildAuthHeaders()});
    const templates = Array.isArray(payload?.templates) ? payload.templates : [];
    subscriptionState.editingLists.template = templates;
    const rows = templates.length === 0 ? emptyConfigList("暂无模板") : templates.map((template) => `
        <div class="subscription-config-row">
            <span class="subscription-config-main">${escapeHtml(template.name)} <small>${escapeHtml(template.typeLabel || "")}</small></span>
            <span class="subscription-config-actions">
                <button class="btn btn-secondary btn-small" type="button" data-config-edit="template" data-config-key="${escapeHtml(template.key)}">编辑</button>
                <button class="btn btn-secondary btn-small subscription-delete" type="button" data-config-delete="template" data-config-key="${escapeHtml(template.key)}">删除</button>
            </span>
        </div>
    `).join("");
    subscriptionEditActionPanel.innerHTML = `
        <div class="subscription-config-list">${rows}</div>
        <div class="modal-actions subscription-config-footer subscription-config-footer--split" data-config-footer>
            <div class="subscription-config-footer-left">
                <label class="template-random-toggle">
                    <input type="checkbox" data-template-random-toggle${payload?.randomEnabled ? " checked" : ""}>
                    <span>开启随机模板</span>
                </label>
            </div>
            <button class="btn btn-primary" type="button" data-config-add="template">添加模板</button>
        </div>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus("");
}

/**
 * 模板表单带类型、名称、正文和占位符说明，正文框保持正方形并允许内部滚动。
 */
function renderTemplateForm(template = null) {
    const type = template?.type || "dynamic";
    setSubscriptionEditTitle(template ? "编辑模板" : "添加模板");
    subscriptionEditActionPanel.innerHTML = `
        <form class="subscription-config-form" data-config-form="template">
            <input type="hidden" name="key" value="${escapeHtml(template?.key || "")}">
            <label class="field">
                <span>模板类型</span>
                <select name="type" data-template-type>
                    <option value="dynamic"${type === "dynamic" ? " selected" : ""}>动态</option>
                    <option value="live"${type === "live" ? " selected" : ""}>开播</option>
                    <option value="liveClose"${type === "liveClose" ? " selected" : ""}>下播</option>
                </select>
            </label>
            <label class="field">
                <span>模板名称</span>
                <input name="name" type="text" autocomplete="off" value="${escapeHtml(template?.name || "")}">
            </label>
            <label class="field">
                <span>模板内容</span>
                <textarea class="template-content-box" name="content">${escapeHtml(template?.content || "")}</textarea>
            </label>
            <pre class="template-explain" data-template-explain>${escapeHtml(templateExplainText[type] || templateExplainText.dynamic)}</pre>
            <div class="modal-actions subscription-config-footer" data-config-footer>
                <button class="btn btn-secondary" type="button" data-config-cancel="template">取消</button>
                <button class="btn btn-primary" type="submit">确认</button>
            </div>
        </form>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus("");
}

/**
 * @全体列表按类型聚合显示群号，删除只移除该类型的当前订阅配置。
 */
async function loadAtAllEditor() {
    subscriptionState.editingAction = "atall";
    renderConfigLoading("已有at信息", "正在加载at全体");
    const payload = await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/atall"), {headers: buildAuthHeaders()});
    const items = Array.isArray(payload?.items) ? payload.items : [];
    subscriptionState.editingLists.atall = items;
    const rows = items.length === 0 ? emptyConfigList("暂无atall信息") : items.map((item) => `
        <div class="subscription-config-row">
            <span class="subscription-config-main">${escapeHtml(item.summary)}</span>
            <span class="subscription-config-actions">
                <button class="btn btn-secondary btn-small" type="button" data-config-edit="atall" data-config-key="${escapeHtml(item.key)}">编辑</button>
                <button class="btn btn-secondary btn-small subscription-delete" type="button" data-config-delete="atall" data-config-key="${escapeHtml(item.key)}">删除</button>
            </span>
        </div>
    `).join("");
    subscriptionEditActionPanel.innerHTML = `
        <div class="subscription-config-list">${rows}</div>
        <div class="modal-actions subscription-config-footer" data-config-footer>
            <button class="btn btn-secondary" type="button" data-editor-back>取消</button>
            <button class="btn btn-primary" type="button" data-config-add="atall">添加at全体</button>
        </div>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus("");
}

/**
 * @全体表单只需要类型选择，作用群组由后端根据当前订阅展开。
 */
function renderAtAllForm(item = null) {
    const targets = getCurrentSubscriptionTargets();
    setSubscriptionEditTitle(item ? "编辑at全体" : "添加at全体");
    const targetOptions = targets.length === 0
        ? '<div class="multi-select-option is-disabled">暂无可选群聊</div>'
        : targets.map((target) => {
            const formatted = formatSubscriptionSubject(target);
            const checked = item?.groups?.includes(target) ? " checked" : "";
            return `
                <label class="multi-select-option">
                    <input type="checkbox" name="targetGroups" value="${escapeHtml(target)}"${checked}>
                    <span>${escapeHtml(formatted.value)}</span>
                </label>
            `;
        }).join("");
    const selectedTargets = targets.filter((target) => item?.groups?.includes(target));
    const selectedLabel = formatMultiSelectSummary(selectedTargets);
    subscriptionEditActionPanel.innerHTML = `
        <form class="subscription-config-form" data-config-form="atall">
            <input type="hidden" name="key" value="${escapeHtml(item?.key || "")}">
            <label class="field">
                <span>at类型</span>
                <select name="type">
                    ${["全部", "全部动态", "直播", "视频", "音乐", "专栏"].map((label) => `<option value="${label}"${item?.type === label ? " selected" : ""}>${label}</option>`).join("")}
                </select>
            </label>
            <label class="field">
                <span>目标群聊</span>
                <div class="multi-select" data-multi-select="targetGroups">
                    <button class="multi-select-trigger" type="button" data-multi-select-trigger aria-expanded="false">
                        <span data-multi-select-label>${escapeHtml(selectedLabel)}</span>
                        <svg viewBox="0 0 24 24" class="multi-select-chevron" aria-hidden="true">
                            <use href="#icon-chevron"></use>
                        </svg>
                    </button>
                    <div class="multi-select-menu" data-multi-select-menu hidden>
                        ${targetOptions}
                    </div>
                </div>
            </label>
            <div class="modal-actions subscription-config-footer" data-config-footer>
                <button class="btn btn-secondary" type="button" data-config-cancel="atall">取消</button>
                <button class="btn btn-primary" type="submit">确认</button>
            </div>
        </form>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus("");
}

/**
 * 当前订阅的可选群聊直接从卡片列表快照里取，避免编辑页自己猜测 scope 来源。
 */
function getCurrentSubscriptionTargets() {
    const item = subscriptionState.items.find((candidate) => candidate.id === subscriptionState.editingItemId);
    return Array.isArray(item?.targets) ? item.targets : [];
}

/**
 * 多选目标群聊在收起状态显示“请选择”或首个群号 +N，避免按钮被长列表撑开。
 */
function formatMultiSelectSummary(values) {
    if (!Array.isArray(values) || values.length === 0) {
        return "请选择目标群聊";
    }
    const formatted = values.map((value) => formatSubscriptionSubject(value).value);
    return formatted.length === 1 ? formatted[0] : `${formatted[0]} +${formatted.length - 1}`;
}

/**
 * 自定义多选框只展开当前一个菜单，保持它像普通选择框一样操作。
 */
function toggleMultiSelect(multiSelect) {
    if (!multiSelect) {
        return;
    }
    const menu = multiSelect.querySelector("[data-multi-select-menu]");
    const trigger = multiSelect.querySelector("[data-multi-select-trigger]");
    const willOpen = Boolean(menu?.hidden);
    closeMultiSelectMenus();
    menu?.toggleAttribute("hidden", !willOpen);
    trigger?.setAttribute("aria-expanded", String(willOpen));
}

/**
 * 关闭全部多选下拉菜单，避免弹窗里保留多个浮层。
 */
function closeMultiSelectMenus() {
    subscriptionEditModal?.querySelectorAll("[data-multi-select]").forEach((multiSelect) => {
        multiSelect.querySelector("[data-multi-select-menu]")?.setAttribute("hidden", "");
        multiSelect.querySelector("[data-multi-select-trigger]")?.setAttribute("aria-expanded", "false");
    });
}

/**
 * 目标群聊勾选变化后同步收起态文案，提交时仍直接读取 checkbox 值。
 */
function updateMultiSelectLabel(multiSelect) {
    if (!multiSelect) {
        return;
    }
    const values = Array.from(multiSelect.querySelectorAll('input[name="targetGroups"]:checked')).map((input) => input.value);
    const label = multiSelect.querySelector("[data-multi-select-label]");
    if (label) {
        label.textContent = formatMultiSelectSummary(values);
    }
}

/**
 * 主题色页面直接读取当前颜色并展示 HEX 输入框，格式错误由前后端双重校验。
 */
async function loadThemeEditor() {
    subscriptionState.editingAction = "theme";
    renderConfigLoading("编辑主题色", "正在加载主题色");
    const payload = await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/theme"), {headers: buildAuthHeaders()});
    subscriptionEditActionPanel.innerHTML = `
        <form class="subscription-config-form" data-config-form="theme">
            <label class="field">
                <span>HEX颜色</span>
                <input name="color" type="text" autocomplete="off" placeholder="#AABBCC" value="${escapeHtml(payload?.color || "")}">
            </label>
            <div class="modal-actions subscription-config-footer" data-config-footer>
                <button class="btn btn-secondary" type="button" data-editor-back>取消</button>
                <button class="btn btn-primary" type="submit">确认</button>
            </div>
        </form>
    `;
    attachSubscriptionEditStatus();
    setSubscriptionEditStatus("");
}

/**
 * 配置页新增和编辑按钮按当前 action 分发到对应表单。
 */
function openConfigForm(action, key = "") {
    if (action === "filter") {
        renderFilterForm(subscriptionState.editingLists.filter.find((item) => item.key === key) || null);
    } else if (action === "template") {
        renderTemplateForm(subscriptionState.editingLists.template.find((item) => item.key === key) || null);
    } else if (action === "atall") {
        renderAtAllForm(subscriptionState.editingLists.atall.find((item) => item.key === key) || null);
    }
}

/**
 * 表单取消时返回当前配置列表，主题色取消则直接回到编辑菜单。
 */
function cancelConfigForm(action) {
    if (action === "filter") {
        loadFilterEditor().catch((error) => setSubscriptionEditStatus(error.message || "过滤器加载失败"));
    } else if (action === "template") {
        loadTemplateEditor().catch((error) => setSubscriptionEditStatus(error.message || "模板加载失败"));
    } else if (action === "atall") {
        loadAtAllEditor().catch((error) => setSubscriptionEditStatus(error.message || "at全体加载失败"));
    } else {
        renderSubscriptionEditMenu();
    }
}

/**
 * 过滤器保存前先做正则必填校验，避免明显错误请求打到后端。
 */
async function submitFilterForm(form) {
    const kind = form.elements.kind.value;
    const content = kind === "regex" ? form.elements.regexContent.value.trim() : form.elements.typeContent.value;
    if (kind === "regex" && !content) {
        setSubscriptionEditStatus("正则内容必须填写");
        return;
    }
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/filters"), {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({
            key: form.elements.key.value,
            kind,
            mode: form.elements.mode.value,
            content,
        }),
    });
    setSubscriptionEditStatus("过滤器已保存", true);
    await loadFilterEditor();
    await refreshSubscriptions();
}

/**
 * 模板保存要求名称存在，正文允许用户自行决定是否为空模板。
 */
async function submitTemplateForm(form) {
    const name = form.elements.name.value.trim();
    if (!name) {
        setSubscriptionEditStatus("模板名称必须填写");
        return;
    }
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/templates"), {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({
            key: form.elements.key.value,
            type: form.elements.type.value,
            name,
            content: form.elements.content.value,
        }),
    });
    setSubscriptionEditStatus("模板已保存", true);
    await loadTemplateEditor();
    await refreshSubscriptions();
}

/**
 * @全体保存提交类型和用户选择的目标群聊，后端负责处理互斥类型和旧群聊替换。
 */
async function submitAtAllForm(form) {
    const oldKey = form.elements.key.value;
    const oldItem = subscriptionState.editingLists.atall.find((item) => item.key === oldKey);
    const targetGroups = Array.from(form.querySelectorAll('input[name="targetGroups"]:checked')).map((input) => input.value);
    if (targetGroups.length === 0) {
        setSubscriptionEditStatus("目标群聊必须至少选择一个");
        return;
    }
    if (oldItem && oldItem.type !== form.elements.type.value) {
        await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl(`/atall/${encodeURIComponent(oldKey)}`), {
            method: "DELETE",
            headers: buildAuthHeaders(),
        });
    }
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/atall"), {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({type: form.elements.type.value, targetGroups}),
    });
    setSubscriptionEditStatus("@全体已保存", true);
    await loadAtAllEditor();
    await refreshSubscriptions();
}

/**
 * 主题色保存前先检查单个 HEX 格式，和后端校验保持同一输入边界。
 */
async function submitThemeForm(form) {
    const color = form.elements.color.value.trim();
    if (!/^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{3})$/.test(color)) {
        setSubscriptionEditStatus("HEX颜色格式错误");
        return;
    }
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/theme"), {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({color}),
    });
    setSubscriptionEditStatus("主题色已保存", true);
    await refreshSubscriptions();
}

/**
 * 删除配置项根据当前类型调用对应接口，删除完成后刷新当前列表和订阅卡片。
 */
async function deleteConfigItem(action, key) {
    const path = action === "filter" ? `/filters/${encodeURIComponent(key)}`
        : action === "template" ? `/templates/${encodeURIComponent(key)}`
            : `/atall/${encodeURIComponent(key)}`;
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl(path), {
        method: "DELETE",
        headers: buildAuthHeaders(),
    });
    if (action === "filter") {
        await loadFilterEditor();
    } else if (action === "template") {
        await loadTemplateEditor();
    } else {
        await loadAtAllEditor();
    }
    await refreshSubscriptions();
}

/**
 * 随机模板开关实时写入策略，失败时把 checkbox 恢复到原状态。
 */
async function toggleTemplateRandom(checkbox) {
    const nextValue = checkbox.checked;
    try {
        await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/templates/random"), {
            method: "POST",
            headers: buildAuthHeaders(true),
            body: JSON.stringify({enabled: nextValue}),
        });
        setSubscriptionEditStatus(nextValue ? "随机模板已开启" : "随机模板已关闭", true);
        await refreshSubscriptions();
    } catch (error) {
        checkbox.checked = !nextValue;
        setSubscriptionEditStatus(error.message || "随机模板保存失败");
    }
}

/**
 * 订阅管理进入可见状态时刷新一次后端快照，保证最后更新时间来自最近加载的数据。
 */
async function refreshSubscriptions() {
    if (!subscriptionList) {
        return;
    }
    if (!subscriptionState.loaded) {
        subscriptionList.innerHTML = '<div class="subscription-empty">订阅加载中</div>';
    }
    const response = await fetch("/api/subscriptions", {headers: buildAuthHeaders()});
    if (response.status === 401 || response.status === 403) {
        location.href = "/login";
        return;
    }
    if (!response.ok) {
        throw new Error(`订阅加载失败：HTTP ${response.status}`);
    }
    const payload = await response.json();
    subscriptionState.items = Array.isArray(payload.items) ? payload.items : [];
    subscriptionState.loaded = true;
    renderSubscriptions(payload);
}

/**
 * 订阅页加载失败时把错误落到列表区域，避免按钮可点但页面静默空白。
 */
function setSubscriptionError(message) {
    if (subscriptionList) {
        subscriptionList.innerHTML = `<div class="subscription-empty">${escapeHtml(message || "订阅加载失败")}</div>`;
    }
}

/**
 * 字节值按二进制单位展示，方便内存和磁盘容量共用同一套文案。
 */
function formatBytes(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value <= 0) {
        return "--";
    }
    const units = ["B", "KB", "MB", "GB", "TB"];
    let scaled = value;
    let unitIndex = 0;
    while (scaled >= 1024 && unitIndex < units.length - 1) {
        scaled /= 1024;
        unitIndex += 1;
    }
    const digits = scaled >= 10 || unitIndex === 0 ? 0 : 1;
    return `${scaled.toFixed(digits)}${units[unitIndex]}`;
}

/**
 * 百分比文案统一保留一位以内的小数，避免卡片宽度随长小数抖动。
 */
function formatPercent(value) {
    const percent = Number(value);
    if (!Number.isFinite(percent)) {
        return "--";
    }
    const clamped = Math.min(100, Math.max(0, percent));
    return `${clamped % 1 === 0 ? clamped.toFixed(0) : clamped.toFixed(1)}%`;
}

/**
 * 资源使用率展示百分比和 used/total，缺少 total 时降级为仅展示百分比。
 */
function formatUsage(usage) {
    if (!usage) {
        return "--";
    }
    const percentText = formatPercent(usage.usagePercent);
    const usedText = formatBytes(usage.usedBytes);
    const totalText = formatBytes(usage.totalBytes);
    if (usedText === "--" || totalText === "--") {
        return percentText;
    }
    return `${percentText} (${usedText} / ${totalText})`;
}

/**
 * 系统负载在不支持的平台显示为不可用，避免把负数或 null 当成真实指标。
 */
function formatSystemLoad(value) {
    const load = Number(value);
    if (!Number.isFinite(load)) {
        return "不可用";
    }
    return load.toFixed(2);
}

/**
 * 将任务层推送类型映射到首页卡片颜色，确保直播、动态和下播一眼可分。
 */
function getPushRecordTypeClass(type) {
    const classes = {
        LIVE: "feed-pill--green",
        DYNAMIC: "feed-pill--purple",
        LIVE_CLOSE: "feed-pill--dark",
    };
    return classes[type] || "feed-pill--dark";
}

/**
 * 首页最近推送记录按后端快照直接渲染，保留类型、状态和摘要，便于快速扫读。
 */
function renderRecentPushRecords(records) {
    const items = Array.isArray(records) ? records : [];
    if (items.length === 0) {
        setRuntimeList(
            "recentPushRecords",
            '<div class="feed-row feed-row--empty"><span class="feed-empty">暂无最近推送记录</span></div>',
        );
        return;
    }

    const rows = items.map((record) => {
        const typeLabel = escapeHtml(record.typeLabel || record.type || "--");
        const statusLabel = escapeHtml(record.statusLabel || (record.success ? "已发送" : "发送失败"));
        const summary = escapeHtml(record.summary || "--");
        const timeLabel = escapeHtml(formatTimeOnly(record.timestampEpochMillis));
        return `
            <div class="feed-row">
                <div class="feed-tags">
                    <span class="feed-pill ${getPushRecordTypeClass(record.type)}">${typeLabel}</span>
                    <span class="feed-pill ${record.success ? "feed-pill--green" : "feed-pill--red"}">${statusLabel}</span>
                </div>
                <span class="feed-text">${summary}</span>
                <time>${timeLabel}</time>
            </div>
        `;
    });
    setRuntimeList("recentPushRecords", rows.join(""));
}

/**
 * 根据后端 host 快照更新运行信息面板。
 */
function renderHostRuntimeStatus(host, uptimeSeconds) {
    const memory = host?.memory || {};
    const storage = host?.storage || {};
    const docker = host?.docker || {};

    setRuntimeField("startedAt", formatDateTime(host?.startedAtEpochMillis));
    setRuntimeField("runtimeDuration", formatUptime(uptimeSeconds).replace(/^已运行\s*/, ""));
    setRuntimeField("systemTime", formatDateTime(host?.systemTimeEpochMillis));
    setRuntimeField("systemLoad", formatSystemLoad(host?.systemLoadAverage));
    setRuntimeField("cpuUsage", formatPercent(host?.cpuUsagePercent));
    setRuntimeField("memoryUsage", formatUsage(memory));
    setRuntimeField("storageUsage", formatUsage(storage));
    setRuntimeField("dockerStatus", docker.detected ? "Docker 运行" : "非 Docker");
    setRuntimeProgress("cpuUsage", host?.cpuUsagePercent);
    setRuntimeProgress("memoryUsage", memory.usagePercent);
    setRuntimeProgress("storageUsage", storage.usagePercent);
}

/**
 * 日志页状态栏统一展示加载、过滤、清空和导出结果，避免按钮静默失败。
 */
function setLogStatus(message) {
    if (logStatus) {
        logStatus.textContent = message;
    }
}

/**
 * Logback 文件行解析保留原始文本，无法识别的续行仍能在搜索和导出里显示。
 */
function parseLogLine(line) {
    const match = String(line ?? "").match(logLinePattern);
    if (!match) {
        return {
            time: "",
            level: "INFO",
            module: "未识别",
            message: line,
            raw: line,
            parsed: false,
        };
    }
    return {
        time: match[1],
        level: match[2],
        module: match[3].trim(),
        message: match[4],
        raw: line,
        parsed: true,
    };
}

/**
 * 多行异常堆栈归并到上一条已解析日志，保证等级和模块筛选不会丢失错误上下文。
 */
function parseLogText(text) {
    const rows = [];
    String(text || "").split(/\r?\n/).filter((line) => line.length > 0).forEach((line) => {
        const row = parseLogLine(line);
        if (!row.parsed && rows.length > 0) {
            const previous = rows[rows.length - 1];
            previous.message = `${previous.message}\n${line}`;
            previous.raw = `${previous.raw}\n${line}`;
            return;
        }
        rows.push(row);
    });
    return rows;
}

/**
 * 日志等级映射到现有颜色类，未知等级降级为 info 视觉而不丢弃内容。
 */
function getLogLevelClass(level) {
    const normalizedLevel = String(level || "INFO").toLowerCase();
    if (normalizedLevel === "warn" || normalizedLevel === "error") {
        return `log-level-${normalizedLevel}`;
    }
    return "log-level-info";
}

/**
 * 当前日志 source 以选择框为准，选择框尚未初始化时回落到状态里的首个来源。
 */
function currentLogSourceId() {
    return logSourceFilter?.value || logState.sourceId || "";
}

/**
 * 来源选择器由后端白名单渲染，确保前端不会构造任意路径读取。
 */
function renderLogSourceOptions(sources) {
    if (!logSourceFilter) {
        return;
    }
    const items = Array.isArray(sources) ? sources : [];
    if (items.length === 0) {
        logSourceFilter.innerHTML = '<option value="">暂无日志来源</option>';
        logState.sourceId = "";
        return;
    }
    const previousSource = currentLogSourceId();
    logSourceFilter.innerHTML = items.map((source) => {
        return `<option value="${escapeHtml(source.id)}">${escapeHtml(source.title || source.id)}</option>`;
    }).join("");
    logState.sourceId = items.some((source) => source.id === previousSource) ? previousSource : items[0].id;
    logSourceFilter.value = logState.sourceId;
}

/**
 * 模块选择器从当前日志窗口自动提取 logger 名称，切换日志来源后同步刷新。
 */
function renderLogModuleOptions() {
    if (!logModuleFilter) {
        return;
    }
    const previousModule = logModuleFilter.value || "ALL";
    const modules = Array.from(new Set(logState.rows.map((row) => row.module).filter(Boolean))).sort();
    logState.modules = modules;
    logModuleFilter.innerHTML = [
        '<option value="ALL">全部模块</option>',
        ...modules.map((moduleName) => `<option value="${escapeHtml(moduleName)}">${escapeHtml(moduleName)}</option>`),
    ].join("");
    logModuleFilter.value = modules.includes(previousModule) ? previousModule : "ALL";
}

/**
 * 日志过滤同时应用等级、模块和关键字，所有条件都只作用于当前受限 tail 窗口。
 */
function filteredLogRows() {
    const selectedLevel = logLevelFilter?.value || "ALL";
    const selectedModule = logModuleFilter?.value || "ALL";
    const keyword = (logSearchInput?.value || "").trim().toLowerCase();
    return logState.rows.filter((row) => {
        const matchesLevel = selectedLevel === "ALL" || row.level === selectedLevel;
        const matchesModule = selectedModule === "ALL" || row.module === selectedModule;
        const searchable = `${row.time} ${row.level} ${row.module} ${row.message} ${row.raw}`.toLowerCase();
        const matchesKeyword = !keyword || searchable.includes(keyword);
        return matchesLevel && matchesModule && matchesKeyword;
    });
}

/**
 * 日志列表整段重绘，避免筛选变化后旧行残留；渲染后固定滚到最新尾部。
 */
function renderLogRows() {
    if (!logList) {
        return;
    }
    const rows = filteredLogRows();
    logState.renderedRows = rows;
    if (rows.length === 0) {
        logList.innerHTML = '<div class="log-row log-row--empty"><span class="log-message">没有匹配的日志</span></div>';
        setLogStatus(`已加载 ${logState.rows.length} 行，当前筛选 0 行`);
        return;
    }
    logList.innerHTML = rows.map((row) => `
        <div class="log-row">
            <span class="log-time">${escapeHtml(row.time || "--")}</span>
            <span class="log-level ${getLogLevelClass(row.level)}">${escapeHtml(row.level)}</span>
            <span class="log-module" title="${escapeHtml(row.module)}">${escapeHtml(row.module || "--")}</span>
            <span class="log-message">${escapeHtml(row.message || row.raw)}</span>
        </div>
    `).join("");
    logList.scrollTop = logList.scrollHeight;
    setLogStatus(`已加载 ${logState.rows.length} 行，当前筛选 ${rows.length} 行`);
}

/**
 * 日志来源只在首次进入日志页时加载，后续刷新复用同一白名单。
 */
async function ensureLogSourcesLoaded() {
    if (logState.sourcesLoaded) {
        return;
    }
    const response = await fetch("/api/logs/sources", {headers: buildAuthHeaders()});
    if (response.status === 401 || response.status === 403) {
        location.href = "/login";
        return;
    }
    if (!response.ok) {
        throw new Error(`日志来源加载失败：HTTP ${response.status}`);
    }
    const payload = await response.json();
    renderLogSourceOptions(payload.sources);
    logState.sourcesLoaded = true;
}

/**
 * 从当前 source 拉取日志 tail；序列号确保慢响应不会覆盖更新后的筛选结果。
 */
async function refreshLogWindow() {
    const requestId = ++logRequestSequence;
    await ensureLogSourcesLoaded();
    const sourceId = currentLogSourceId();
    if (!sourceId) {
        logState.rows = [];
        renderLogModuleOptions();
        renderLogRows();
        setLogStatus("暂无可用日志来源");
        return;
    }

    setLogStatus("正在刷新日志");
    const response = await fetch(`/api/logs/${encodeURIComponent(sourceId)}?tail=${logTailLines}`, {headers: buildAuthHeaders()});
    if (response.status === 401 || response.status === 403) {
        location.href = "/login";
        return;
    }
    if (!response.ok) {
        throw new Error(`日志刷新失败：HTTP ${response.status}`);
    }
    const payload = await response.json();
    if (requestId !== logRequestSequence) {
        return;
    }
    const text = payload.text || "";
    logState.sourceId = payload.sourceId || sourceId;
    logState.rows = parseLogText(text);
    renderLogModuleOptions();
    renderLogRows();
    if (payload.sourceMissing) {
        setLogStatus(`${payload.title || sourceId} 尚未生成日志文件`);
    }
}

/**
 * 自动刷新只在日志页可见且开关开启时运行，减少后台无意义请求。
 */
function startLogAutoRefresh() {
    stopLogAutoRefresh();
    if (!logAutoRefresh?.checked) {
        return;
    }
    logRefreshTimer = setInterval(() => {
        refreshLogWindow().catch((error) => setLogStatus(error.message || "日志刷新失败"));
    }, logRefreshIntervalMs);
}

/**
 * 离开日志页或关闭自动刷新时清理定时器，避免重复轮询。
 */
function stopLogAutoRefresh() {
    if (logRefreshTimer) {
        clearInterval(logRefreshTimer);
        logRefreshTimer = null;
    }
}

/**
 * 进入日志页时立即加载一次日志，并按开关状态启动后续自动刷新。
 */
function handleLogViewActivated() {
    refreshLogWindow()
        .then(startLogAutoRefresh)
        .catch((error) => setLogStatus(error.message || "日志加载失败"));
}

/**
 * 清空日志调用后端固定 source 截断接口，完成后立即刷新当前窗口。
 */
async function clearCurrentLog() {
    const sourceId = currentLogSourceId();
    if (!sourceId) {
        setLogStatus("暂无可清空的日志来源");
        return;
    }
    if (!window.confirm("确认清空当前日志来源的内容？")) {
        return;
    }
    const response = await fetch(`/api/logs/${encodeURIComponent(sourceId)}/clear`, {
        method: "POST",
        headers: buildAuthHeaders(),
    });
    if (response.status === 401 || response.status === 403) {
        location.href = "/login";
        return;
    }
    if (!response.ok) {
        throw new Error(`清空日志失败：HTTP ${response.status}`);
    }
    const payload = await response.json();
    setLogStatus(payload.sourceMissing ? "日志文件尚未生成，无需清空" : "日志已清空");
    await refreshLogWindow();
}

/**
 * 导出内容使用当前筛选后的可见行，方便排查时直接下载精简日志片段。
 */
function exportCurrentLog() {
    const rows = logState.renderedRows.length > 0 ? logState.renderedRows : filteredLogRows();
    const text = rows.map((row) => row.raw).join("\n");
    const blob = new Blob([text ? `${text}\n` : ""], {type: "text/plain;charset=utf-8"});
    const anchor = document.createElement("a");
    const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
    anchor.href = URL.createObjectURL(blob);
    anchor.download = `dynamic-bot-${currentLogSourceId() || "logs"}-${timestamp}.log`;
    document.body.appendChild(anchor);
    anchor.click();
    URL.revokeObjectURL(anchor.href);
    anchor.remove();
    setLogStatus(`已导出 ${rows.length} 行日志`);
}

/**
 * 根据 runtime summary 更新首页五张状态卡。
 */
function renderRuntimeSummary(summary) {
    const botStatus = formatLifecycleState(summary.lifecycleState);
    const account = summary.account || {};
    const socket = summary.webSocket || {};
    const pushStats = summary.todayPushStats || {};

    setRuntimeField("sidebarVersion", summary.appVersion || "--");
    setRuntimeField("sidebarStatus", botStatus);
    setRuntimeField("botStatus", botStatus);
    setRuntimeField("botUptime", formatUptime(summary.uptimeSeconds));
    setRuntimeField("subscriptionTotal", String(summary.subscriptionCount ?? 0));
    setRuntimeField(
        "subscriptionBreakdown",
        `UP主：${summary.dynamicSubscriptionCount ?? 0}　番剧：${summary.bangumiSubscriptionCount ?? 0}`,
    );
    setRuntimeField("biliAccountStatus", account.loggedIn ? "已登录" : (account.cookieConfigured ? "待确认" : "未登录"));
    setRuntimeField("biliAccountUid", account.uid ? `UID: ${account.uid}` : "UID: --");
    setRuntimeField("webSocketStatus", socket.connected ? "已连接" : "未连接");
    setRuntimeField(
        "webSocketDetail",
        `重连次数：${socket.reconnectAttempts ?? 0}　会话：${socket.activeSessionCount ?? 0}`,
    );
    setRuntimeField("todayPushTotal", `${pushStats.total ?? 0} 条`);
    setRuntimeField(
        "todayPushBreakdown",
        `直播：${pushStats.live ?? 0}　动态：${pushStats.dynamic ?? 0}　下播：${pushStats.liveClose ?? 0}`,
    );
    renderRecentPushRecords(summary.recentPushRecords);
    renderHostRuntimeStatus(summary.host, summary.uptimeSeconds);
}

/**
 * 账号菜单展开态只由按钮的 aria-expanded 和菜单 hidden 状态决定，避免视觉态与可访问状态分叉。
 */
function setAdminMenuOpen(open) {
    if (!adminMenuButton || !adminMenu) {
        return;
    }
    adminMenu.hidden = !open;
    adminMenuButton.setAttribute("aria-expanded", String(open));
}

/**
 * 改密弹窗每次打开都清空旧输入和提示，防止敏感内容或错误文案跨会话残留。
 */
function openChangePasswordModal() {
    if (!changePasswordModal || !modalCurrentPasswordInput || !modalNewPasswordInput || !modalConfirmPasswordInput) {
        return;
    }
    setAdminMenuOpen(false);
    modalCurrentPasswordInput.value = "";
    modalNewPasswordInput.value = "";
    modalConfirmPasswordInput.value = "";
    setPasswordModalStatus("");
    changePasswordModal.hidden = false;
    modalCurrentPasswordInput.focus();
}

/**
 * 关闭改密弹窗时同步清理密码框，避免用户取消后旧密码留在 DOM 中。
 */
function closeChangePasswordModal() {
    if (!changePasswordModal || !modalCurrentPasswordInput || !modalNewPasswordInput || !modalConfirmPasswordInput) {
        return;
    }
    modalCurrentPasswordInput.value = "";
    modalNewPasswordInput.value = "";
    modalConfirmPasswordInput.value = "";
    setPasswordModalStatus("");
    changePasswordModal.hidden = true;
}

/**
 * 弹窗底部状态统一处理成功和失败颜色，让旧密码错误等服务端校验结果固定显示在窗口底部。
 */
function setPasswordModalStatus(message, success = false) {
    if (!modalPasswordStatus) {
        return;
    }
    modalPasswordStatus.textContent = message;
    modalPasswordStatus.classList.toggle("is-success", success);
}

/**
 * 登出成功或会话失效都回到登录页，避免浏览器继续停留在已经无法刷新数据的主壳。
 */
async function logoutAndReturnToLogin() {
    try {
        await fetch("/api/auth/logout", {
            method: "POST",
            headers: buildAuthHeaders(),
        });
    } finally {
        sessionStorage.removeItem("webuiToken");
        window.location.replace("/login");
    }
}

/**
 * 主壳改密先做本地必填和确认密码一致性校验，再交给后端验证旧密码和密码策略。
 */
async function submitPasswordChange() {
    const currentPassword = modalCurrentPasswordInput?.value || "";
    const newPassword = modalNewPasswordInput?.value || "";
    const confirmPassword = modalConfirmPasswordInput?.value || "";
    if (!currentPassword || !newPassword || !confirmPassword) {
        setPasswordModalStatus("请完整填写旧密码、新密码和确认新密码");
        return;
    }
    if (newPassword !== confirmPassword) {
        setPasswordModalStatus("新密码和确认密码不一致");
        return;
    }

    const response = await fetch("/api/auth/change-password", {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({
            currentPassword,
            newPassword,
        }),
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
        const message = payload.message === "invalid credentials"
            ? "旧密码错误"
            : (payload.message || `修改密码失败：HTTP ${response.status}`);
        setPasswordModalStatus(message);
        return;
    }
    setPasswordModalStatus("密码已修改，请重新登录", true);
    sessionStorage.removeItem("webuiToken");
    setTimeout(() => window.location.replace("/login"), 500);
}

/**
 * 调用受认证保护的运行态接口；认证失效时回到登录页完成重新登录。
 */
async function refreshRuntimeSummary() {
    try {
        const response = await fetch("/api/runtime/summary", {headers: buildAuthHeaders()});
        if (response.status === 401 || response.status === 403) {
            location.href = "/login";
            return;
        }
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        renderRuntimeSummary(await response.json());
    } catch (error) {
        setRuntimeField("botStatus", "状态刷新失败");
        setRuntimeField("botUptime", error.message || "请稍后重试");
    }
}

/**
 * 系统配置快照按文件保存 token，页面层按分类保存，避免跨文件 owner 被一次提交混写。
 */
async function loadSettingsFiles(force = false) {
    if (settingsState.loaded && !force) {
        return;
    }
    setSettingsStatus("正在加载系统配置");
    const [biliConfig, botConfig] = await Promise.all([
        fetchConfigFile("/api/config/bili-config"),
        fetchConfigFile("/api/config/bot"),
    ]);
    applySettingsFile("biliConfig", biliConfig);
    applySettingsFile("botConfig", botConfig);
    settingsState.loaded = true;
    setSettingsStatus("");
}

/**
 * 配置接口统一处理认证失效跳转和 HTTP 错误，调用方只关心可用 JSON 快照。
 */
async function fetchConfigFile(url) {
    const response = await fetch(url, {headers: buildAuthHeaders()});
    if (response.status === 401 || response.status === 403) {
        window.location.replace("/login");
        throw new Error("请重新登录");
    }
    if (!response.ok) {
        throw new Error(`配置加载失败：HTTP ${response.status}`);
    }
    return response.json();
}

/**
 * 后端字段列表转换成 Map 后，渲染和 payload builder 可以按稳定字段路径读取值。
 */
function applySettingsFile(fileKey, payload) {
    const fileState = settingsState.files[fileKey];
    fileState.sourceFile = payload.sourceFile || fileState.sourceFile;
    fileState.snapshotToken = payload.snapshotToken || "";
    fileState.fieldsByKey = new Map((payload.fields || []).map((field) => {
        return [field.key, {...field, originalValue: field.value}];
    }));
}

/**
 * 字段读取统一降级，避免缺省配置未展开时让表单显示 undefined。
 */
function fieldValue(fileKey, key, fallback = "") {
    const value = settingsState.files[fileKey]?.fieldsByKey?.get(key)?.value;
    if (value === undefined || value === null) {
        return fallback;
    }
    return String(value);
}

/**
 * 重启字段比较固定读取加载时的原始值，避免平台切换重绘表单时覆盖变更基线。
 */
function previousRestartSettingValue(fileKey, key, fallback = "") {
    const field = settingsState.files[fileKey]?.fieldsByKey?.get(key);
    const value = field?.originalValue ?? field?.value;
    if (value === undefined || value === null) {
        return fallback;
    }
    return String(value);
}

/**
 * 页面状态只写入当前可见配置面板，避免多个 tab 同时出现过期提示。
 */
function setSettingsStatus(message, success = false) {
    settingsState.status = message || "";
    const status = document.querySelector(`[data-settings-panel="${settingsState.activeTab}"] .settings-status`);
    if (status) {
        status.textContent = settingsState.status;
        status.classList.toggle("is-success", Boolean(success));
    }
}

/**
 * 每个设置分区声明自己触达的配置文件，保存时按文件独立提交 payload。
 */
function settingsSectionFileKeys(sectionName) {
    const mapping = {
        integration: ["botConfig"],
        feature: ["biliConfig"],
        bili: ["biliConfig"],
        polling: ["biliConfig"],
        render: ["biliConfig"],
        message: ["biliConfig"],
        admin: ["biliConfig", "botConfig"],
        translate: ["biliConfig"],
    };
    return mapping[sectionName] || [];
}

/**
 * 当前分区保存前收集表单字段，再由文件级 builder 合成完整写请求。
 */
async function saveSettingsSection(sectionName) {
    const form = document.querySelector(`[data-settings-form="${sectionName}"]`);
    if (!form) {
        return;
    }
    const values = collectSettingsForm(form);
    const validationErrors = validateSettingsSection(sectionName, values);
    if (validationErrors.length > 0) {
        setSettingsStatus(validationErrors.join("；"));
        return;
    }
    const confirmationPassword = window.prompt("请输入 WebUI 密码确认保存") || "";
    if (!confirmationPassword) {
        setSettingsStatus("已取消保存");
        return;
    }
    const fileKeys = settingsSectionFileKeys(sectionName);
    const savePlans = fileKeys.map((fileKey) => ({
        fileKey,
        payload: fileKey === "biliConfig"
            ? buildBiliConfigSettingsPayload(sectionName, values, confirmationPassword)
            : buildBotConfigSettingsPayload(sectionName, values, confirmationPassword),
    }));
    const restartRequiredChanged = savePlans.some((plan) => {
        return settingsPayloadHasRestartRequiredChanges(plan.fileKey, plan.payload);
    });
    setSettingsStatus("正在保存");
    for (const plan of savePlans) {
        if (plan.fileKey === "biliConfig") {
            await postBiliConfigSettings(plan.payload);
        }
        if (plan.fileKey === "botConfig") {
            await postBotConfigSettings(plan.payload);
        }
    }
    await loadSettingsFiles(true);
    renderSettingsActiveTab();
    setSettingsStatus("保存成功", true);
    if (restartRequiredChanged) {
        showRestartRequiredModal();
    }
}

/**
 * BiliConfig 保存接口只接收 BiliConfig.yml 字段，成功后刷新本地文件 token。
 */
async function postBiliConfigSettings(payload) {
    const result = await postSettingsPayload("/api/config/bili-config", payload);
    settingsState.files.biliConfig.snapshotToken = result.snapshotToken || settingsState.files.biliConfig.snapshotToken;
    return result;
}

/**
 * bot.yml 保存接口只接收平台和 WebUI 字段，成功后刷新本地文件 token。
 */
async function postBotConfigSettings(payload) {
    const result = await postSettingsPayload("/api/config/bot", payload);
    settingsState.files.botConfig.snapshotToken = result.snapshotToken || settingsState.files.botConfig.snapshotToken;
    return result;
}

/**
 * 配置保存统一解析后端保存结果，把冲突、确认失败和校验失败转成可读状态。
 */
async function postSettingsPayload(url, payload) {
    const response = await fetch(url, {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify(payload),
    });
    if (response.status === 401 || response.status === 403) {
        window.location.replace("/login");
        throw new Error("请重新登录");
    }
    const result = await response.json().catch(() => ({}));
    if (!response.ok || result.success === false) {
        if (response.status === 409 || result.conflictDetected) {
            throw new Error("配置已变化，请刷新后重试");
        }
        const detail = Array.isArray(result.validationErrors) && result.validationErrors.length > 0
            ? result.validationErrors.join("；")
            : (result.message || `保存失败：HTTP ${response.status}`);
        throw new Error(detail);
    }
    return result;
}

/**
 * 重启提示只比较 WebUI 当前暴露的重启字段，避免 bot.yml 文件级重启建议误伤热生效字段。
 */
function settingsPayloadHasRestartRequiredChanges(fileKey, payload) {
    const keyMap = restartRequiredPayloadKeyByFile[fileKey] || {};
    const restartKeys = restartRequiredSettingKeysByFile[fileKey] || new Set();
    return Object.entries(keyMap).some(([payloadPath, settingsKey]) => {
        if (!restartKeys.has(settingsKey)) {
            return false;
        }
        const nextValue = readPayloadPath(payload, payloadPath);
        if (nextValue === undefined) {
            return false;
        }
        if (restartMaskedSettingKeys.has(settingsKey) && String(nextValue ?? "").trim() === "") {
            return false;
        }
        const previousValue = previousRestartSettingValue(fileKey, settingsKey, "");
        return normalizeRestartComparableValue(nextValue) !== normalizeRestartComparableValue(previousValue);
    });
}

/**
 * 读取 payload 中的点分路径，支持 cacheExpires.DRAW 这类嵌套字段比较。
 */
function readPayloadPath(payload, path) {
    return String(path || "").split(".").reduce((current, segment) => {
        if (current === undefined || current === null) {
            return undefined;
        }
        return current[segment];
    }, payload);
}

/**
 * 重启字段比较需要把数组、JSON 字符串、布尔值和数字都收敛成稳定文本。
 */
function normalizeRestartComparableValue(value) {
    if (value === undefined || value === null) {
        return "";
    }
    if (Array.isArray(value)) {
        return JSON.stringify(value.map((item) => String(item).trim()).filter(Boolean));
    }
    if (typeof value === "object") {
        return JSON.stringify(Object.keys(value).sort().reduce((acc, key) => {
            acc[key] = normalizeRestartComparableValue(value[key]);
            return acc;
        }, {}));
    }
    const text = String(value).trim();
    try {
        const parsed = JSON.parse(text);
        if (Array.isArray(parsed) || (parsed && typeof parsed === "object")) {
            return normalizeRestartComparableValue(parsed);
        }
    } catch (_) {
        // 非 JSON 字符串按普通配置值比较。
    }
    return text;
}

/**
 * 重启提示弹窗只能通过确认按钮关闭，避免用户误以为保存失败或需要继续处理当前表单。
 */
function showRestartRequiredModal() {
    if (restartRequiredModal) {
        restartRequiredModal.hidden = false;
    }
}

/**
 * 确认按钮关闭重启提示，保留页面当前状态供用户继续检查配置。
 */
function closeRestartRequiredModal() {
    if (restartRequiredModal) {
        restartRequiredModal.hidden = true;
    }
}

/**
 * BiliConfig payload 从当前快照补齐非本分区字段，确保文件级 DTO 不会被局部表单清空。
 */
function buildBiliConfigSettingsPayload(sectionName, values, confirmationPassword = "") {
    const read = (key, fallback = "") => values[key] ?? fieldValue("biliConfig", key, fallback);
    return {
        snapshotToken: settingsState.files.biliConfig.snapshotToken,
        admin: settingsLong(fieldValue("biliConfig", "admin"), 0),
        adminContact: values.adminContactQQ !== undefined
            ? adminContactFromQQ(values.adminContactQQ)
            : read("adminContact"),
        debugMode: settingsBool(read("enableConfig.debugMode")),
        drawEnable: settingsBool(read("enableConfig.drawEnable", "true")),
        pushDrawEnable: settingsBool(read("enableConfig.pushDrawEnable", "true")),
        notifyEnable: settingsBool(read("enableConfig.notifyEnable", "true")),
        liveCloseNotifyEnable: settingsBool(read("enableConfig.liveCloseNotifyEnable", "true")),
        lowSpeedEnable: settingsBool(read("enableConfig.lowSpeedEnable", "true")),
        translateEnable: settingsBool(read("enableConfig.translateEnable")),
        proxyEnable: settingsBool(read("enableConfig.proxyEnable")),
        cacheClearEnable: settingsBool(read("enableConfig.cacheClearEnable", "true")),
        cookie: values["accountConfig.cookie"] || "",
        autoFollow: settingsBool(read("accountConfig.autoFollow", "true")),
        followGroup: read("accountConfig.followGroup", "Bot关注"),
        proxies: settingsLines(read("proxyConfig.proxy")),
        lowSpeedTime: read("checkConfig.lowSpeedTime", "22-8"),
        lowSpeedRange: read("checkConfig.lowSpeedRange", "60-240"),
        normalRange: read("checkConfig.normalRange", "30-120"),
        checkReportInterval: settingsInt(read("checkConfig.checkReportInterval"), 10),
        timeout: settingsInt(read("checkConfig.timeout"), 10),
        quality: read("imageConfig.quality", "1000w"),
        theme: read("imageConfig.theme", "v3"),
        font: read("imageConfig.font"),
        defaultColor: read("imageConfig.defaultColor", "#d3edfa"),
        cardOrnament: read("imageConfig.cardOrnament", "FanCard"),
        timeDisplayMode: read("imageConfig.timeDisplayMode", "ABSOLUTE"),
        hueStep: settingsInt(read("imageConfig.colorGenerator.hueStep"), 30),
        lockSB: settingsBool(read("imageConfig.colorGenerator.lockSB", "true")),
        saturation: settingsFloat(read("imageConfig.colorGenerator.saturation"), 0.25),
        brightness: settingsFloat(read("imageConfig.colorGenerator.brightness"), 1),
        leftBadgeEnable: values["imageConfig.badgeEnable.choice"] === undefined
            ? settingsBool(read("imageConfig.badgeEnable.left", "true"))
            : badgeEnableFromChoice(read("imageConfig.badgeEnable.choice"), "left"),
        rightBadgeEnable: values["imageConfig.badgeEnable.choice"] === undefined
            ? settingsBool(read("imageConfig.badgeEnable.right"))
            : badgeEnableFromChoice(read("imageConfig.badgeEnable.choice"), "right"),
        dynamicFooter: read("templateConfig.footer.dynamicFooter"),
        liveFooter: read("templateConfig.footer.liveFooter"),
        footerAlign: read("templateConfig.footer.footerAlign", "LEFT"),
        downloadOriginal: settingsBool(read("cacheConfig.downloadOriginal", "true")),
        cacheExpires: {
            DRAW: settingsInt(read("cacheConfig.expires.DRAW"), 7),
            IMAGES: settingsInt(read("cacheConfig.expires.IMAGES"), 7),
            EMOJI: settingsInt(read("cacheConfig.expires.EMOJI"), 7),
            USER: settingsInt(read("cacheConfig.expires.USER"), 7),
            OTHER: settingsInt(read("cacheConfig.expires.OTHER"), 7),
        },
        messageInterval: settingsLong(read("pushConfig.messageInterval"), 100),
        pushInterval: settingsLong(read("pushConfig.pushInterval"), 500),
        toShortLink: settingsBool(read("pushConfig.toShortLink")),
        defaultDynamicPush: read("templateConfig.defaultDynamicPush", "OneMsg"),
        defaultLivePush: read("templateConfig.defaultLivePush", "OneMsg"),
        defaultLiveClose: read("templateConfig.defaultLiveClose", "SimpleMsg"),
        dynamicPush: values["templateConfig.dynamicPush"] === undefined ? {} : settingsKeyValueMap(read("templateConfig.dynamicPush")),
        livePush: values["templateConfig.livePush"] === undefined ? {} : settingsKeyValueMap(read("templateConfig.livePush")),
        liveClose: values["templateConfig.liveClose"] === undefined ? {} : settingsKeyValueMap(read("templateConfig.liveClose")),
        triggerMode: read("linkResolveConfig.triggerMode", "At"),
        linkResolveDrawEnable: settingsBool(read("linkResolveConfig.drawEnable", "true")),
        linkResolveReturnLink: settingsBool(read("linkResolveConfig.returnLink")),
        cutLine: read("translateConfig.cutLine"),
        baiduAppId: read("translateConfig.baidu.APP_ID"),
        baiduSecurityKey: values["translateConfig.baidu.SECURITY_KEY"] || "",
        confirmationPassword,
    };
}

/**
 * bot.yml payload 同样从快照补齐非当前表单字段，并保留空 secret 的 write-only 语义。
 */
function buildBotConfigSettingsPayload(sectionName, values, confirmationPassword = "") {
    const read = (key, fallback = "") => values[key] ?? fieldValue("botConfig", key, fallback);
    return {
        snapshotToken: settingsState.files.botConfig.snapshotToken,
        platformType: read("platform.type", "onebot11"),
        adapter: read("platform.adapter", "onebot11"),
        oneBot11Host: read("platform.onebot11.host", "127.0.0.1"),
        oneBot11Port: settingsInt(read("platform.onebot11.port"), 3001),
        oneBot11Token: values["platform.onebot11.token"] || "",
        oneBot11UseTls: settingsBool(read("platform.onebot11.useTls")),
        oneBot11HeartbeatInterval: settingsLong(read("platform.onebot11.heartbeatInterval"), 30000),
        oneBot11ReconnectInterval: settingsLong(read("platform.onebot11.reconnectInterval"), 5000),
        oneBot11MessageFormat: read("platform.onebot11.messageFormat", "array"),
        oneBot11SendMode: read("platform.onebot11.sendMode", "base64"),
        oneBot11MaxReconnectAttempts: settingsInt(read("platform.onebot11.maxReconnectAttempts"), -1),
        oneBot11ConnectTimeout: settingsLong(read("platform.onebot11.connectTimeout"), 10000),
        qqOfficialAppId: read("platform.qqOfficial.appId"),
        qqOfficialAppSecret: values["platform.qqOfficial.appSecret"] || "",
        qqOfficialBotToken: values["platform.qqOfficial.botToken"] || "",
        webUiEnabled: settingsBool(read("webui.enabled")),
        webUiHost: read("webui.host", "127.0.0.1"),
        webUiPort: settingsInt(read("webui.port"), 18080),
        webUiCredentialFile: read("webui.credentialFile", "webui-credentials.json"),
        webUiTokenTtlSeconds: settingsLong(read("webui.tokenTtlSeconds"), 3600),
        webUiStaticDir: read("webui.staticDir"),
        targets: settingsJsonList(read("targets")),
        admins: values.adminsText === undefined ? settingsJsonList(read("admins")) : parseAdminLines(values.adminsText),
        confirmationPassword,
    };
}

/**
 * 选择框和 checkbox 的布尔值统一收敛，避免字符串 false 在 JS 中被误判为 truthy。
 */
function settingsBool(value) {
    return value === true || String(value).toLowerCase() === "true" || String(value) === "1";
}

/**
 * 数字字段转换失败时回退到后端默认值，保持 payload 结构稳定。
 */
function settingsInt(value, fallback) {
    const number = Number.parseInt(value, 10);
    return Number.isFinite(number) ? number : fallback;
}

/**
 * Long 字段在浏览器中按安全整数提交，当前配置值都位于安全范围内。
 */
function settingsLong(value, fallback) {
    return settingsInt(value, fallback);
}

/**
 * 浮点字段只接受有限数字，避免 NaN 被 JSON 序列化成 null。
 */
function settingsFloat(value, fallback) {
    const number = Number.parseFloat(value);
    return Number.isFinite(number) ? number : fallback;
}

/**
 * 多行列表去除空白行后提交，代理地址和联系人列表共享这一规则。
 */
function settingsLines(value) {
    const text = jsonArrayToLines(value);
    return String(text || "")
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean);
}

/**
 * JSON 数组字段用于 textarea 展示时转成逐行文本，解析失败则保留原文本。
 */
function jsonArrayToLines(value) {
    const text = String(value || "");
    try {
        const parsed = JSON.parse(text);
        if (Array.isArray(parsed)) {
            return parsed.join("\n");
        }
    } catch (_) {
        return text;
    }
    return text;
}

/**
 * 模板映射用 key=value 的多行文本编辑，保存时恢复成对象。
 */
function settingsKeyValueMap(value) {
    const raw = String(value || "");
    try {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
            return parsed;
        }
    } catch (_) {
        // 继续按 key=value 文本解析。
    }
    return raw.split(/\r?\n/).reduce((acc, line) => {
        const index = line.indexOf("=");
        if (index > 0) {
            acc[line.slice(0, index).trim()] = line.slice(index + 1);
        }
        return acc;
    }, {});
}

/**
 * targets/admins 高级字段以 JSON 保存，解析失败时提交空列表交由后端校验。
 */
function settingsJsonList(value) {
    try {
        const parsed = JSON.parse(String(value || "[]"));
        return Array.isArray(parsed) ? parsed : [];
    } catch (_) {
        return [];
    }
}

/**
 * 超级管理员 QQ 仍由前端展示为数字，保存时写入平台联系人格式的 admin_contact。
 */
function adminContactFromQQ(value) {
    const text = String(value || "").trim();
    return text ? `onebot11:private:${text}` : "";
}

/**
 * admin_contact 读取时尽量还原为 QQ 数字，兼容旧 admin 数字字段作为显示兜底。
 */
function qqFromAdminContact() {
    const contact = fieldValue("biliConfig", "adminContact", "").trim();
    const matched = contact.match(/^onebot11:private:(\d+)$/);
    if (matched) {
        return matched[1];
    }
    return fieldValue("biliConfig", "admin", "");
}

/**
 * 徽章选择框只是前端聚合展示，提交时仍拆回后端已有的 left/right 布尔字段。
 */
function badgeChoiceFromFields() {
    const left = settingsBool(fieldValue("biliConfig", "imageConfig.badgeEnable.left", "true"));
    const right = settingsBool(fieldValue("biliConfig", "imageConfig.badgeEnable.right"));
    return right && !left ? "right" : "left";
}

/**
 * 根据聚合选择结果判断具体徽章位是否启用，避免改动后端绘制配置结构。
 */
function badgeEnableFromChoice(choice, side) {
    const normalized = String(choice || "").trim();
    return normalized === side;
}

/**
 * 群普通管理员按“群号:QQ号”逐行录入，同一群多行会合并成后端现有 DTO。
 */
function parseAdminLines(value) {
    const grouped = new Map();
    String(value || "").split(/\r?\n/).forEach((line, index) => {
        const text = line.trim();
        if (!text) {
            return;
        }
        const matched = text.match(/^(\d+)\s*[:：]\s*(\d+)$/);
        if (!matched) {
            throw new Error(`第 ${index + 1} 行格式应为 群号:QQ号`);
        }
        const groupId = Number.parseInt(matched[1], 10);
        const userId = Number.parseInt(matched[2], 10);
        if (!grouped.has(groupId)) {
            grouped.set(groupId, new Set());
        }
        grouped.get(groupId).add(userId);
    });
    return Array.from(grouped.entries()).map(([groupId, userIds]) => ({
        groupId,
        userIds: Array.from(userIds),
        groupContact: `onebot11:group:${groupId}`,
        userContacts: Array.from(userIds).map((userId) => `onebot11:private:${userId}`),
    }));
}

/**
 * 现有管理员 JSON 快照转换成逐行文本，便于用户按简单格式继续编辑。
 */
function adminLinesFromSnapshot() {
    return settingsJsonList(fieldValue("botConfig", "admins", "[]")).flatMap((item) => {
        const groupId = Number(item?.groupId) || groupIdFromContact(item?.groupContact);
        const userIds = Array.isArray(item?.userIds) && item.userIds.length > 0
            ? item.userIds
            : (Array.isArray(item?.userContacts) ? item.userContacts.map(userIdFromContact).filter(Boolean) : []);
        if (!groupId) {
            return [];
        }
        return userIds.map((userId) => `${groupId}:${userId}`);
    }).join("\n");
}

/**
 * 只读摘要展示每个群的前两个管理员，更多成员按 +N 折叠，避免长列表撑高卡片。
 */
function formatAdminSummary() {
    const admins = settingsJsonList(fieldValue("botConfig", "admins", "[]"));
    if (admins.length === 0) {
        return '<p class="settings-readonly-empty">暂无群普通管理员</p>';
    }
    return admins.map((item) => {
        const groupId = Number(item?.groupId) || groupIdFromContact(item?.groupContact);
        const userIds = Array.isArray(item?.userIds) && item.userIds.length > 0
            ? item.userIds
            : (Array.isArray(item?.userContacts) ? item.userContacts.map(userIdFromContact).filter(Boolean) : []);
        const shown = userIds.slice(0, 2).join("、") || "无";
        const extra = userIds.length > 2 ? ` +${userIds.length - 2}` : "";
        return `<p>群聊：${escapeHtml(groupId || "-")} 管理员：${escapeHtml(shown)}${escapeHtml(extra)}</p>`;
    }).join("");
}

/**
 * 从群联系人 subject 中提取数字群号，无法识别时返回 0 供调用方跳过。
 */
function groupIdFromContact(contact) {
    const matched = String(contact || "").match(/^onebot11:group:(\d+)$/);
    return matched ? Number.parseInt(matched[1], 10) : 0;
}

/**
 * 从私聊联系人 subject 中提取数字 QQ，无法识别时返回 0 供调用方跳过。
 */
function userIdFromContact(contact) {
    const matched = String(contact || "").match(/^onebot11:private:(\d+)$/);
    return matched ? Number.parseInt(matched[1], 10) : 0;
}

/**
 * 当前表单字段按 name 收集，checkbox 使用 checked，其余控件使用 value。
 */
function collectSettingsForm(form) {
    return Array.from(form.elements).reduce((values, element) => {
        if (!element.name || element.disabled) {
            return values;
        }
        values[element.name] = element.type === "checkbox" ? String(element.checked) : element.value;
        return values;
    }, {});
}

/**
 * 分类级前端校验只覆盖 WebUI 自己改变了展示格式的字段，后端仍负责最终配置合法性。
 */
function validateSettingsSection(sectionName, values) {
    const errors = [];
    if (sectionName === "integration") {
        const platformType = String(values["platform.type"] || "onebot11").trim();
        if (platformType !== "qq_official") {
            errors.push(...validatePortValue("OneBot11 端口", values["platform.onebot11.port"]));
            errors.push(...validatePositiveIntegerValue("心跳间隔", values["platform.onebot11.heartbeatInterval"]));
            errors.push(...validatePositiveIntegerValue("重连间隔", values["platform.onebot11.reconnectInterval"]));
            errors.push(...validatePositiveIntegerValue("连接超时", values["platform.onebot11.connectTimeout"]));
        }
        errors.push(...validatePortValue("WebUI 端口", values["webui.port"]));
        errors.push(...validatePositiveIntegerValue("会话有效秒数", values["webui.tokenTtlSeconds"]));
    }
    if (sectionName === "polling") {
        errors.push(...validateHourRangeValue("低频时段", values["checkConfig.lowSpeedTime"]));
        errors.push(...validateIntervalRangeValue("低频间隔", values["checkConfig.lowSpeedRange"]));
        errors.push(...validateIntervalRangeValue("正常间隔", values["checkConfig.normalRange"]));
        errors.push(...validatePositiveIntegerValue("状态报告间隔", values["checkConfig.checkReportInterval"]));
        errors.push(...validatePositiveIntegerValue("请求超时", values["checkConfig.timeout"]));
    }
    if (sectionName === "render") {
        errors.push(...validateGradientHexColorValue("默认颜色", values["imageConfig.defaultColor"]));
        ["DRAW", "IMAGES", "EMOJI", "USER", "OTHER"].forEach((key) => {
            errors.push(...validatePositiveIntegerValue(`缓存 ${key}`, values[`cacheConfig.expires.${key}`]));
        });
    }
    if (sectionName === "message") {
        errors.push(...validatePositiveIntegerValue("消息间隔", values["pushConfig.messageInterval"]));
        errors.push(...validatePositiveIntegerValue("推送间隔", values["pushConfig.pushInterval"]));
    }
    if (sectionName === "admin") {
        errors.push(...validateOptionalPositiveIntegerValue("超级管理员 QQ", values.adminContactQQ));
    }
    if (sectionName !== "admin" || values.adminsText === undefined) {
        return errors;
    }
    try {
        parseAdminLines(values.adminsText);
        return errors;
    } catch (error) {
        return errors.concat(error.message || "群普通管理员格式错误");
    }
}

/**
 * 正整数校验用于 WebUI 可编辑的 Int/Long 配置，避免空值、小数或负数被隐式回退。
 */
function validatePositiveIntegerValue(label, value, minimum = 1) {
    const text = String(value ?? "").trim();
    if (!/^\d+$/.test(text)) {
        return [`${label}必须是正整数`];
    }
    const number = BigInt(text);
    if (number < BigInt(minimum)) {
        return [`${label}不能小于 ${minimum}`];
    }
    return [];
}

/**
 * 可选正整数用于超级管理员 QQ，允许清空配置但不允许保存 0、负数或非数字。
 */
function validateOptionalPositiveIntegerValue(label, value) {
    const text = String(value ?? "").trim();
    if (!text) {
        return [];
    }
    return validatePositiveIntegerValue(label, text);
}

/**
 * 端口号按计算机端口范围 1-65535 校验，前端先拦截明显不可启动的 WebUI/OneBot11 参数。
 */
function validatePortValue(label, value) {
    const errors = validatePositiveIntegerValue(label, value);
    if (errors.length > 0) {
        return errors;
    }
    const port = Number.parseInt(String(value).trim(), 10);
    if (port < 1 || port > 65535) {
        return [`${label}必须在 1-65535 之间`];
    }
    return [];
}

/**
 * 低频时段只接受 0-23 的“开始-结束”小时写法，允许 22-8 这种跨午夜时段。
 */
function validateHourRangeValue(label, value) {
    const range = parseSettingsRange(value);
    if (!range || range.min < 0 || range.max < 0 || range.min > 23 || range.max > 23 || range.min === range.max) {
        return [`${label}必须是 0-23 的小时范围，例如 22-8`];
    }
    return [];
}

/**
 * 低频和正常轮询间隔共享“最低-最大”格式，最低间隔必须满足程序内 30 秒下限。
 */
function validateIntervalRangeValue(label, value) {
    const range = parseSettingsRange(value);
    if (!range) {
        return [`${label}必须是 最低-最大 的正整数范围`];
    }
    if (range.min < 30 || range.max < 30) {
        return [`${label}最低间隔不能小于 30 秒`];
    }
    if (range.min > range.max) {
        return [`${label}最低间隔不能大于最大间隔`];
    }
    return [];
}

/**
 * 区间解析只接受非负整数字面量，避免负号、小数或额外字符进入后端配置。
 */
function parseSettingsRange(value) {
    const matched = String(value ?? "").trim().match(/^(\d+)\s*-\s*(\d+)$/);
    if (!matched) {
        return null;
    }
    return {
        min: Number.parseInt(matched[1], 10),
        max: Number.parseInt(matched[2], 10),
    };
}

/**
 * 默认颜色沿用运行期渐变色格式：1-4 个 #RRGGBB HEX 色值，可用分号分隔。
 */
function validateGradientHexColorValue(label, value) {
    const text = String(value ?? "").trim();
    const segments = text.split(/[;；]/).map((segment) => segment.trim());
    if (segments.length === 0 || segments.length > 4 || segments.some((segment) => !/^#[0-9A-Fa-f]{6}$/.test(segment))) {
        return [`${label}必须是 #RRGGBB 格式，多个颜色最多 4 个并用分号分隔`];
    }
    return [];
}

/**
 * 当前选中 tab 由对应 renderer 接管，未加载时显示稳定加载态。
 */
function renderSettingsActiveTab() {
    const renderers = {
        integration: renderIntegrationSettings,
        feature: renderFeatureSettings,
        bili: renderBiliSettings,
        polling: renderPollingSettings,
        render: renderRenderSettings,
        message: renderMessageSettings,
        admin: renderAdminSettings,
        translate: renderTranslateSettings,
    };
    const panel = settingsPanel(settingsState.activeTab);
    if (!panel) {
        return;
    }
    if (!settingsState.loaded) {
        panel.innerHTML = '<div class="settings-panel"><p class="settings-status">正在加载系统配置</p></div>';
        return;
    }
    renderers[settingsState.activeTab]?.();
}

/**
 * 面板查找固定走 data-settings-panel，避免后续调整 HTML id 时破坏渲染。
 */
function settingsPanel(tabName) {
    return document.querySelector(`[data-settings-panel="${tabName}"]`);
}

/**
 * 每个配置分区共享标题、状态栏和保存按钮，只替换中间表单内容。
 */
function renderSettingsShell(tabName, bodyHtml, options = {}) {
    const panel = settingsPanel(tabName);
    if (!panel) {
        return;
    }
    const sourceFiles = (settingsSectionFileKeys(tabName) || [])
        .map((key) => settingsState.files[key]?.sourceFile)
        .filter(Boolean)
        .join(" / ");
    const readonly = options.readonly === true;
    panel.className = "settings-panel";
    panel.innerHTML = `
        <div class="settings-panel-head">
            <div>
                <h2>${escapeHtml(options.title || "系统配置")}</h2>
                <p>${escapeHtml(sourceFiles || "只读信息")}</p>
            </div>
            <span class="settings-status" role="status">${escapeHtml(settingsState.status)}</span>
        </div>
        ${readonly ? bodyHtml : `<form data-settings-form="${escapeHtml(tabName)}">${bodyHtml}
            <div class="settings-actions">
                <button class="btn btn-secondary btn-small" type="button" data-settings-refresh>刷新本分类</button>
                <button class="btn btn-primary btn-small" type="submit">保存本分类</button>
            </div>
        </form>`}
    `;
}

/**
 * 普通输入字段统一渲染标签、控件和当前值，长字段由 wide 控制跨列。
 */
function renderSettingField(field) {
    const value = field.value ?? fieldValue(field.file, field.key, field.fallback || "");
    const wide = field.wide ? " settings-field--wide" : "";
    const placeholder = field.placeholder ? ` placeholder="${escapeHtml(field.placeholder)}"` : "";
    const inputAttributes = settingInputAttributes(field);
    return `<label class="settings-field${wide}">
        <span>${escapeHtml(field.label)}</span>
        <input name="${escapeHtml(field.key)}" type="${escapeHtml(field.type || "text")}" value="${escapeHtml(value)}"${placeholder}${inputAttributes} autocomplete="off">
    </label>`;
}

/**
 * 带单位字段只用于已明确单位的配置项，单位作为输入框内右侧装饰而不参与提交值。
 */
function renderSettingFieldWithUnit(field) {
    const value = field.value ?? fieldValue(field.file, field.key, field.fallback || "");
    const wide = field.wide ? " settings-field--wide" : "";
    const placeholder = field.placeholder ? ` placeholder="${escapeHtml(field.placeholder)}"` : "";
    const inputAttributes = settingInputAttributes(field);
    return `<label class="settings-field${wide}">
        <span>${escapeHtml(field.label)}</span>
        <span class="settings-input-unit">
            <input name="${escapeHtml(field.key)}" type="${escapeHtml(field.type || "text")}" value="${escapeHtml(value)}"${placeholder}${inputAttributes} autocomplete="off">
            <span class="settings-unit" aria-hidden="true">${escapeHtml(field.unit)}</span>
        </span>
    </label>`;
}

/**
 * 输入附加属性集中生成，给浏览器原生控件提供第一层范围和键盘提示。
 */
function settingInputAttributes(field) {
    const attributes = [];
    ["min", "max", "step", "pattern", "inputmode"].forEach((name) => {
        if (field[name] !== undefined) {
            attributes.push(`${name}="${escapeHtml(field[name])}"`);
        }
    });
    return attributes.length > 0 ? ` ${attributes.join(" ")}` : "";
}

/**
 * 枚举字段使用 select 保持候选值稳定，避免用户输入运行时不认识的文本。
 */
function renderSettingSelect(field) {
    const value = field.value ?? fieldValue(field.file, field.key, field.fallback || "");
    const options = (field.options || []).map((option) => {
        const selected = String(option.value) === String(value) ? " selected" : "";
        return `<option value="${escapeHtml(option.value)}"${selected}>${escapeHtml(option.label)}</option>`;
    }).join("");
    return `<label class="settings-field">
        <span>${escapeHtml(field.label)}</span>
        <select name="${escapeHtml(field.key)}">${options}</select>
    </label>`;
}

/**
 * 长文本字段使用 textarea 并固定最小高度，避免模板内容撑动布局。
 */
function renderSettingTextarea(field) {
    const value = field.value ?? fieldValue(field.file, field.key, field.fallback || "");
    const wide = field.wide === false ? "" : " settings-field--wide";
    return `<label class="settings-field${wide}">
        <span>${escapeHtml(field.label)}</span>
        <textarea name="${escapeHtml(field.key)}" rows="${field.rows || 4}">${escapeHtml(value)}</textarea>
    </label>`;
}

/**
 * 敏感字段只提交用户本次输入，placeholder 提示空值会保留原配置。
 */
function renderSecretField(field) {
    return `<label class="settings-field settings-secret">
        <span>${escapeHtml(field.label)}</span>
        <input name="${escapeHtml(field.key)}" type="password" value="" placeholder="留空则保留原值" autocomplete="new-password">
    </label>`;
}

/**
 * 列表编辑器复用 textarea，每行一个值，保存前由 settingsLines 规整。
 */
function renderListTextarea(field) {
    return renderSettingTextarea({
        ...field,
        value: jsonArrayToLines(field.value ?? fieldValue(field.file, field.key, "")),
        rows: field.rows || 5,
    });
}

/**
 * 对接配置按平台和 WebUI 启动参数分成两个卡片，避免运行入口和管理面配置混在一起。
 */
function renderIntegrationSettings() {
    const platform = fieldValue("botConfig", "platform.type", "onebot11");
    const oneBotFields = platform !== "qq_official" ? [
        renderSettingSelect({file: "botConfig", key: "platform.adapter", label: "OneBot11 适配器", options: [
            {value: "onebot11", label: "通用"},
            {value: "napcat", label: "NapCat"},
            {value: "llbot", label: "llbot"},
        ]}),
        renderSettingField({file: "botConfig", key: "platform.onebot11.host", label: "OneBot11 主机"}),
        renderSettingField({file: "botConfig", key: "platform.onebot11.port", label: "OneBot11 端口", type: "number", min: 1, max: 65535, step: 1}),
        renderSecretField({key: "platform.onebot11.token", label: "OneBot11 Token"}),
        renderSettingSelect({file: "botConfig", key: "platform.onebot11.useTls", label: "TLS", options: boolOptions()}),
        renderSettingFieldWithUnit({file: "botConfig", key: "platform.onebot11.heartbeatInterval", label: "心跳间隔", type: "number", unit: "毫秒", min: 1, step: 1}),
        renderSettingFieldWithUnit({file: "botConfig", key: "platform.onebot11.reconnectInterval", label: "重连间隔", type: "number", unit: "毫秒", min: 1, step: 1}),
        renderSettingSelect({file: "botConfig", key: "platform.onebot11.sendMode", label: "图片发送方式", options: [
            {value: "base64", label: "base64"},
            {value: "file", label: "file"},
        ]}),
        renderSettingField({file: "botConfig", key: "platform.onebot11.maxReconnectAttempts", label: "最大重连次数", type: "number"}),
        renderSettingFieldWithUnit({file: "botConfig", key: "platform.onebot11.connectTimeout", label: "连接超时", type: "number", unit: "毫秒", min: 1, step: 1}),
    ].join("") : "";
    const qqFields = platform === "qq_official" ? [
        renderSettingField({file: "botConfig", key: "platform.qqOfficial.appId", label: "QQ App ID"}),
        renderSecretField({key: "platform.qqOfficial.appSecret", label: "QQ App Secret"}),
        renderSecretField({key: "platform.qqOfficial.botToken", label: "QQ Bot Token"}),
    ].join("") : "";
    renderSettingsShell("integration", `
        <div class="settings-card-stack">
            <section class="settings-subcard">
                <h3>对接平台</h3>
                <div class="settings-section-grid">
                    ${renderSettingSelect({file: "botConfig", key: "platform.type", label: "对接平台", options: [
                        {value: "onebot11", label: "通用机器人协议"},
                        {value: "qq_official", label: "QQ 官方机器人"},
                    ]})}
                    ${oneBotFields}
                    ${qqFields}
                </div>
            </section>
            <section class="settings-subcard">
                <h3>WebUI</h3>
                <div class="settings-section-grid">
                    ${renderSettingSelect({file: "botConfig", key: "webui.enabled", label: "启用 WebUI", options: boolOptions()})}
                    ${renderSettingField({file: "botConfig", key: "webui.host", label: "WebUI 主机"})}
                    ${renderSettingField({file: "botConfig", key: "webui.port", label: "WebUI 端口", type: "number", min: 1, max: 65535, step: 1})}
                    ${renderSettingField({file: "botConfig", key: "webui.tokenTtlSeconds", label: "会话有效秒数", type: "number", min: 1, step: 1})}
                </div>
            </section>
        </div>`, {title: "对接配置"});
}

/**
 * 功能开关全部落在 BiliConfig.yml 的 enableConfig 下。
 */
function renderFeatureSettings() {
    renderSettingsShell("feature", `<div class="settings-section-grid">
        ${[
            ["enableConfig.debugMode", "调试模式"],
            ["enableConfig.drawEnable", "启用绘图"],
            ["enableConfig.pushDrawEnable", "推送绘图"],
            ["enableConfig.notifyEnable", "开播通知"],
            ["enableConfig.liveCloseNotifyEnable", "下播通知"],
            ["enableConfig.lowSpeedEnable", "低频轮询"],
            ["enableConfig.translateEnable", "翻译"],
            ["enableConfig.proxyEnable", "代理"],
            ["enableConfig.cacheClearEnable", "缓存清理"],
        ].map(([key, label]) => renderSettingSelect({file: "biliConfig", key, label, options: boolOptions()})).join("")}
    </div>`, {title: "功能开关"});
}

/**
 * B 站配置集中账号、关注分组和代理列表。
 */
function renderBiliSettings() {
    renderSettingsShell("bili", `<div class="settings-section-grid">
        ${renderSecretField({key: "accountConfig.cookie", label: "B站 Cookie"})}
        ${renderSettingSelect({file: "biliConfig", key: "accountConfig.autoFollow", label: "自动关注", options: boolOptions()})}
        ${renderSettingField({file: "biliConfig", key: "accountConfig.followGroup", label: "关注分组"})}
        ${renderListTextarea({file: "biliConfig", key: "proxyConfig.proxy", label: "代理地址", wide: true})}
    </div>`, {title: "B站配置"});
}

/**
 * 轮询配置保留原有区间文本格式，由后端和运行期共同解释。
 */
function renderPollingSettings() {
    renderSettingsShell("polling", `<div class="settings-section-grid">
        ${renderSettingFieldWithUnit({file: "biliConfig", key: "checkConfig.lowSpeedTime", label: "低频时段", unit: "小时", placeholder: "22-8", inputmode: "numeric"})}
        ${renderSettingFieldWithUnit({file: "biliConfig", key: "checkConfig.lowSpeedRange", label: "低频间隔", unit: "秒", placeholder: "60-240", inputmode: "numeric"})}
        ${renderSettingFieldWithUnit({file: "biliConfig", key: "checkConfig.normalRange", label: "正常间隔", unit: "秒", placeholder: "30-120", inputmode: "numeric"})}
        ${renderSettingFieldWithUnit({file: "biliConfig", key: "checkConfig.checkReportInterval", label: "状态报告间隔", type: "number", min: 1, step: 1, unit: "秒"})}
        ${renderSettingFieldWithUnit({file: "biliConfig", key: "checkConfig.timeout", label: "请求超时", type: "number", min: 1, step: 1, unit: "秒"})}
    </div>`, {title: "轮询配置"});
}

/**
 * 渲染配置覆盖图片、页脚和缓存过期时间，部分字段可能需要重启后完全生效。
 */
function renderRenderSettings() {
    renderSettingsShell("render", `<div class="settings-section-grid">
        ${renderSettingField({file: "biliConfig", key: "imageConfig.quality", label: "图片质量"})}
        ${renderSettingField({file: "biliConfig", key: "imageConfig.theme", label: "主题"})}
        ${renderSettingField({file: "biliConfig", key: "imageConfig.font", label: "字体", placeholder: "留空则使用内置字体"})}
        ${renderSettingField({file: "biliConfig", key: "imageConfig.defaultColor", label: "默认颜色", placeholder: "#d3edfa"})}
        ${renderSettingSelect({file: "biliConfig", key: "imageConfig.cardOrnament", label: "右侧装饰", options: [
            {value: "FanCard", label: "粉丝卡"},
            {value: "QrCode", label: "二维码"},
            {value: "", label: "不绘制"},
        ]})}
        ${renderSettingSelect({file: "biliConfig", key: "imageConfig.timeDisplayMode", label: "时间显示", options: [
            {value: "ABSOLUTE", label: "绝对时间"},
            {value: "RELATIVE", label: "相对时间"},
        ]})}
        ${renderSettingField({file: "biliConfig", key: "imageConfig.colorGenerator.hueStep", label: "色相步进", type: "number"})}
        ${renderSettingSelect({file: "biliConfig", key: "imageConfig.colorGenerator.lockSB", label: "锁定明度饱和", options: boolOptions()})}
        ${renderSettingField({file: "biliConfig", key: "imageConfig.colorGenerator.saturation", label: "饱和度", type: "number"})}
        ${renderSettingField({file: "biliConfig", key: "imageConfig.colorGenerator.brightness", label: "亮度", type: "number"})}
        ${renderSettingSelect({file: "biliConfig", key: "imageConfig.badgeEnable.choice", label: "徽章", value: badgeChoiceFromFields(), options: [
            {value: "left", label: "左徽章"},
            {value: "right", label: "右徽章"},
        ]})}
        ${renderSettingTextarea({file: "biliConfig", key: "templateConfig.footer.dynamicFooter", label: "动态页脚"})}
        ${renderSettingTextarea({file: "biliConfig", key: "templateConfig.footer.liveFooter", label: "直播页脚"})}
        ${renderSettingSelect({file: "biliConfig", key: "templateConfig.footer.footerAlign", label: "页脚对齐", options: [
            {value: "LEFT", label: "左"},
            {value: "CENTER", label: "中"},
            {value: "RIGHT", label: "右"},
        ]})}
        ${renderSettingSelect({file: "biliConfig", key: "cacheConfig.downloadOriginal", label: "下载原图", options: boolOptions()})}
        ${["DRAW", "IMAGES", "EMOJI", "USER", "OTHER"].map((key) => renderSettingFieldWithUnit({file: "biliConfig", key: `cacheConfig.expires.${key}`, label: `缓存 ${key}`, type: "number", min: 1, step: 1, unit: "天"})).join("")}
    </div>`, {title: "渲染配置"});
}

/**
 * 消息配置覆盖推送节流、模板默认值、模板表和链接解析策略。
 */
function renderMessageSettings() {
    renderSettingsShell("message", `<div class="settings-section-grid">
        ${renderSettingFieldWithUnit({file: "biliConfig", key: "pushConfig.messageInterval", label: "消息间隔", type: "number", min: 1, step: 1, unit: "毫秒"})}
        ${renderSettingFieldWithUnit({file: "biliConfig", key: "pushConfig.pushInterval", label: "推送间隔", type: "number", min: 1, step: 1, unit: "毫秒"})}
        ${renderSettingSelect({file: "biliConfig", key: "pushConfig.toShortLink", label: "转短链", options: boolOptions()})}
        ${renderSettingSelect({file: "biliConfig", key: "templateConfig.defaultDynamicPush", label: "动态默认模板", options: templateOptions()})}
        ${renderSettingSelect({file: "biliConfig", key: "templateConfig.defaultLivePush", label: "直播默认模板", options: templateOptions()})}
        ${renderSettingSelect({file: "biliConfig", key: "templateConfig.defaultLiveClose", label: "下播默认模板", options: [
            {value: "SimpleMsg", label: "简洁下播"},
            {value: "ComplexMsg", label: "详细下播"},
        ]})}
        ${renderSettingSelect({file: "biliConfig", key: "linkResolveConfig.triggerMode", label: "链接解析触发", options: [
            {value: "At", label: "被提及时"},
            {value: "Always", label: "总是"},
            {value: "Never", label: "关闭"},
        ]})}
        ${renderSettingSelect({file: "biliConfig", key: "linkResolveConfig.drawEnable", label: "链接解析绘图", options: boolOptions()})}
        ${renderSettingSelect({file: "biliConfig", key: "linkResolveConfig.returnLink", label: "返回链接", options: boolOptions()})}
    </div>`, {title: "消息配置"});
}

/**
 * 管理员配置横跨 BiliConfig 管理员和 bot.yml 群管理员映射，保存时按文件拆分提交。
 */
function renderAdminSettings() {
    renderSettingsShell("admin", `<div class="settings-section-grid">
        ${renderSettingField({key: "adminContactQQ", label: "超级管理员 QQ", type: "number", min: 1, step: 1, value: qqFromAdminContact()})}
        ${renderSettingTextarea({key: "adminsText", label: "群普通管理员", value: adminLinesFromSnapshot(), wide: true, rows: 8})}
        <div class="settings-readonly settings-field--wide">
            <span>当前已有配置</span>
            <div class="settings-readonly-list">${formatAdminSummary()}</div>
        </div>
    </div>`, {title: "管理员"});
}

/**
 * 翻译配置保留 Baidu secret 的 write-only 行为，空提交不覆盖旧密钥。
 */
function renderTranslateSettings() {
    renderSettingsShell("translate", `<div class="settings-section-grid">
        ${renderSettingTextarea({file: "biliConfig", key: "translateConfig.cutLine", label: "翻译分隔线", wide: true})}
        ${renderSettingField({file: "biliConfig", key: "translateConfig.baidu.APP_ID", label: "百度 APP ID"})}
        ${renderSecretField({key: "translateConfig.baidu.SECURITY_KEY", label: "百度密钥"})}
    </div>`, {title: "翻译配置"});
}

/**
 * 开关选项固定使用 true/false 字符串，和后端 Boolean DTO 对齐。
 */
function boolOptions() {
    return [
        {value: "true", label: "开"},
        {value: "false", label: "关"},
    ];
}

/**
 * 动态和直播模板默认值共享同一组选项。
 */
function templateOptions() {
    return [
        {value: "DrawOnly", label: "只发图片"},
        {value: "TextOnly", label: "纯文本"},
        {value: "OneMsg", label: "单条消息"},
        {value: "TwoMsg", label: "双条消息"},
    ];
}

/**
 * 系统配置页顶栏只维护当前分类的选中态和空白占位显隐，后续字段归类不需要改动导航骨架。
 */
function activateSettingsTab(tabName) {
    const fallbackName = settingsTabButtons[0]?.dataset.settingsTab || "";
    const targetName = settingsTabButtons.some((button) => button.dataset.settingsTab === tabName)
        ? tabName
        : fallbackName;
    settingsTabButtons.forEach((button) => {
        const active = button.dataset.settingsTab === targetName;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", String(active));
    });
    settingsPanels.forEach((panel) => {
        panel.hidden = panel.dataset.settingsPanel !== targetName;
    });
    settingsState.activeTab = targetName;
    renderSettingsActiveTab();
}

/**
 * 首页统计卡快捷入口先同步设置子页，再切换 hash，确保从首页直达对应配置分类。
 */
function navigateMetricShortcut(button) {
    const targetView = button.dataset.metricNav || defaultView;
    const settingsTab = button.dataset.settingsTabTarget || "";
    if (settingsTab) {
        activateSettingsTab(settingsTab);
    }
    if (location.hash.replace(/^#/, "") === targetView) {
        activateView(targetView);
        return;
    }
    location.hash = targetView;
}

/**
 * 页面壳负责在可见页面之间切换，运行态数据刷新由独立函数处理。
 */
function activateView(viewName, replaceHash = false) {
    const targetName = views.has(viewName) ? viewName : defaultView;
    navItems.forEach((item) => {
        const active = item.dataset.navTarget === targetName;
        item.classList.toggle("is-active", active);
        item.setAttribute("aria-pressed", String(active));
    });
    views.forEach((view, name) => {
        const active = name === targetName;
        view.hidden = !active;
        view.classList.toggle("is-active", active);
    });

    const label = viewTitles[targetName] ?? viewTitles[defaultView];
    if (pageTitle) {
        pageTitle.textContent = label;
    }
    document.title = `dynamic-bot · ${label}`;
    if (contentArea) {
        contentArea.scrollTop = 0;
    }
    if (replaceHash) {
        history.replaceState(null, "", `#${targetName}`);
    }
    if (targetName === "logs") {
        handleLogViewActivated();
    } else if (targetName === "subscriptions") {
        stopLogAutoRefresh();
        refreshSubscriptions().catch((error) => setSubscriptionError(error.message || "订阅加载失败"));
    } else if (targetName === "settings") {
        stopLogAutoRefresh();
        loadSettingsFiles().then(renderSettingsActiveTab).catch((error) => setSettingsStatus(error.message || "配置加载失败"));
    } else {
        stopLogAutoRefresh();
    }
}

/**
 * 侧边栏导航通过 hash 保持当前静态视图，便于直接刷新回到同一页。
 */
navItems.forEach((item) => {
    item.addEventListener("click", () => {
        const target = item.dataset.navTarget || defaultView;
        location.hash = target;
    });
});

metricNavButtons.forEach((button) => {
    button.addEventListener("click", () => {
        navigateMetricShortcut(button);
    });
});

settingsTabButtons.forEach((button) => {
    button.addEventListener("click", () => {
        activateSettingsTab(button.dataset.settingsTab || "");
    });
});

settingsPanels.forEach((panel) => {
    panel.addEventListener("submit", (event) => {
        const form = event.target.closest("[data-settings-form]");
        if (!form) {
            return;
        }
        event.preventDefault();
        saveSettingsSection(form.dataset.settingsForm).catch((error) => {
            setSettingsStatus(error.message || "保存失败");
        });
    });
    panel.addEventListener("click", (event) => {
        if (!event.target.closest("[data-settings-refresh]")) {
            return;
        }
        loadSettingsFiles(true).then(renderSettingsActiveTab).catch((error) => setSettingsStatus(error.message || "刷新失败"));
    });
    panel.addEventListener("change", (event) => {
        if (event.target.name === "platform.type") {
            const field = settingsState.files.botConfig.fieldsByKey.get("platform.type");
            if (field) {
                settingsState.files.botConfig.fieldsByKey.set("platform.type", {...field, value: event.target.value});
            }
            renderIntegrationSettings();
        }
    });
});

if (logSourceFilter) {
    logSourceFilter.addEventListener("change", () => {
        logState.sourceId = logSourceFilter.value;
        refreshLogWindow().catch((error) => setLogStatus(error.message || "日志刷新失败"));
    });
}

if (logLevelFilter) {
    logLevelFilter.addEventListener("change", renderLogRows);
}

if (logModuleFilter) {
    logModuleFilter.addEventListener("change", renderLogRows);
}

if (logSearchInput) {
    logSearchInput.addEventListener("input", renderLogRows);
}

// 订阅筛选按钮只更新前端筛选态，避免每次切换标签都重新请求后端。
subscriptionFilterButtons.forEach((button) => {
    button.addEventListener("click", () => {
        subscriptionState.filter = button.dataset.subscriptionFilter || "all";
        renderSubscriptions();
    });
});

if (subscriptionSearchInput) {
    // 搜索框即时过滤当前快照，接口刷新时会保留用户输入的关键字。
    subscriptionSearchInput.addEventListener("input", () => {
        subscriptionState.search = subscriptionSearchInput.value || "";
        renderSubscriptions();
    });
}

if (addSubscriptionButton) {
    addSubscriptionButton.addEventListener("click", openSubscriptionModal);
}

if (closeSubscriptionModalButton) {
    closeSubscriptionModalButton.addEventListener("click", closeSubscriptionModal);
}

if (cancelSubscriptionModalButton) {
    cancelSubscriptionModalButton.addEventListener("click", closeSubscriptionModal);
}

if (subscriptionCreateType) {
    subscriptionCreateType.addEventListener("change", updateSubscriptionCreateFields);
}

if (subscriptionCreateForm) {
    // 表单提交只走新增订阅接口，校验错误留在弹窗底部反馈。
    subscriptionCreateForm.addEventListener("submit", (event) => {
        event.preventDefault();
        createSubscription().catch((error) => setSubscriptionModalStatus(error.message || "添加失败"));
    });
}

if (subscriptionList) {
    // 卡片按钮由列表容器代理，避免每次刷新后重新绑定全部按钮。
    subscriptionList.addEventListener("click", (event) => {
        const editButton = event.target.closest("[data-subscription-edit]");
        if (editButton) {
            openSubscriptionEditModal(editButton.dataset.subscriptionEdit || "");
            return;
        }
        const deleteButton = event.target.closest("[data-subscription-delete]");
        if (deleteButton) {
            openSubscriptionDeleteModal(deleteButton.dataset.subscriptionDelete || "");
        }
    });
}

if (closeSubscriptionEditButton) {
    closeSubscriptionEditButton.addEventListener("click", closeSubscriptionEditModal);
}

if (subscriptionEditModal) {
    // 编辑弹窗内部使用事件代理，列表刷新和表单切换后不需要重新绑定按钮。
    subscriptionEditModal.addEventListener("click", (event) => {
        const multiSelectTrigger = event.target.closest("[data-multi-select-trigger]");
        if (multiSelectTrigger) {
            toggleMultiSelect(multiSelectTrigger.closest("[data-multi-select]"));
            return;
        }
        if (!event.target.closest("[data-multi-select]")) {
            closeMultiSelectMenus();
        }
        const actionButton = event.target.closest("[data-edit-action]");
        if (actionButton) {
            const action = actionButton.dataset.editAction;
            const loaders = {
                filter: loadFilterEditor,
                template: loadTemplateEditor,
                atall: loadAtAllEditor,
                theme: loadThemeEditor,
            };
            loaders[action]?.().catch((error) => setSubscriptionEditStatus(error.message || "配置加载失败"));
            return;
        }
        if (event.target.closest("[data-editor-back]")) {
            renderSubscriptionEditMenu();
            setSubscriptionEditStatus("");
            return;
        }
        const addButton = event.target.closest("[data-config-add]");
        if (addButton) {
            openConfigForm(addButton.dataset.configAdd);
            return;
        }
        const editButton = event.target.closest("[data-config-edit]");
        if (editButton) {
            openConfigForm(editButton.dataset.configEdit, editButton.dataset.configKey || "");
            return;
        }
        const deleteButton = event.target.closest("[data-config-delete]");
        if (deleteButton) {
            deleteConfigItem(deleteButton.dataset.configDelete, deleteButton.dataset.configKey || "")
                .catch((error) => setSubscriptionEditStatus(error.message || "删除失败"));
            return;
        }
        const cancelButton = event.target.closest("[data-config-cancel]");
        if (cancelButton) {
            cancelConfigForm(cancelButton.dataset.configCancel);
        }
    });

    // 表单提交统一从当前 form 的 data-config-form 分发到对应保存逻辑。
    subscriptionEditModal.addEventListener("submit", (event) => {
        const form = event.target.closest("[data-config-form]");
        if (!form) {
            return;
        }
        event.preventDefault();
        const action = form.dataset.configForm;
        const submitters = {
            filter: submitFilterForm,
            template: submitTemplateForm,
            atall: submitAtAllForm,
            theme: submitThemeForm,
        };
        submitters[action]?.(form).catch((error) => setSubscriptionEditStatus(error.message || "保存失败"));
    });

    // 选择过滤方式和模板类型时同步切换字段显隐和说明文案，避免用户保存前看到旧说明。
    subscriptionEditModal.addEventListener("change", (event) => {
        const filterKind = event.target.closest("[data-filter-kind]");
        if (filterKind) {
            const showRegex = filterKind.value === "regex";
            subscriptionEditModal.querySelector("[data-filter-regex-field]")?.toggleAttribute("hidden", !showRegex);
            subscriptionEditModal.querySelector("[data-filter-type-field]")?.toggleAttribute("hidden", showRegex);
            return;
        }
        const templateType = event.target.closest("[data-template-type]");
        if (templateType) {
            const explain = subscriptionEditModal.querySelector("[data-template-explain]");
            if (explain) {
                explain.textContent = templateExplainText[templateType.value] || templateExplainText.dynamic;
            }
            return;
        }
        const randomToggle = event.target.closest("[data-template-random-toggle]");
        if (randomToggle) {
            toggleTemplateRandom(randomToggle);
            return;
        }
        const targetGroupOption = event.target.closest('input[name="targetGroups"]');
        if (targetGroupOption) {
            updateMultiSelectLabel(targetGroupOption.closest("[data-multi-select]"));
        }
    });
}

if (closeSubscriptionDeleteButton) {
    closeSubscriptionDeleteButton.addEventListener("click", closeSubscriptionDeleteModal);
}

if (cancelSubscriptionDeleteButton) {
    cancelSubscriptionDeleteButton.addEventListener("click", closeSubscriptionDeleteModal);
}

if (confirmSubscriptionDeleteButton) {
    // 删除确认按钮绑定真实删除请求，失败文案留在列表或弹窗状态区域展示。
    confirmSubscriptionDeleteButton.addEventListener("click", () => {
        confirmSubscriptionDelete().catch((error) => {
            if (subscriptionDeleteStatus) {
                subscriptionDeleteStatus.textContent = error.message || "删除失败";
            }
        });
    });
}

if (logAutoRefresh) {
    logAutoRefresh.addEventListener("change", () => {
        if (logAutoRefresh.checked && !views.get("logs")?.hidden) {
            refreshLogWindow()
                .then(startLogAutoRefresh)
                .catch((error) => setLogStatus(error.message || "日志刷新失败"));
            return;
        }
        stopLogAutoRefresh();
    });
}

if (logClearButton) {
    logClearButton.addEventListener("click", () => {
        clearCurrentLog().catch((error) => setLogStatus(error.message || "清空日志失败"));
    });
}

if (logExportButton) {
    logExportButton.addEventListener("click", exportCurrentLog);
}

themePreferenceButtons.forEach((button) => {
    button.addEventListener("click", () => {
        applyThemePreference(button.dataset.themeOption || "system");
    });
});

if (adminMenuButton) {
    adminMenuButton.addEventListener("click", (event) => {
        event.stopPropagation();
        setAdminMenuOpen(adminMenu?.hidden !== false);
    });
}

if (openChangePasswordButton) {
    openChangePasswordButton.addEventListener("click", openChangePasswordModal);
}

if (logoutButton) {
    logoutButton.addEventListener("click", logoutAndReturnToLogin);
}

if (closeChangePasswordButton) {
    closeChangePasswordButton.addEventListener("click", closeChangePasswordModal);
}

if (cancelChangePasswordButton) {
    cancelChangePasswordButton.addEventListener("click", closeChangePasswordModal);
}

if (confirmRestartRequiredButton) {
    confirmRestartRequiredButton.addEventListener("click", closeRestartRequiredModal);
}

if (changePasswordModal) {
    changePasswordModal.addEventListener("click", (event) => {
        if (event.target === changePasswordModal) {
            closeChangePasswordModal();
        }
    });
}

if (modalChangePasswordForm) {
    modalChangePasswordForm.addEventListener("submit", (event) => {
        event.preventDefault();
        submitPasswordChange().catch((error) => {
            setPasswordModalStatus(error.message || "修改密码失败，请稍后重试");
        });
    });
}

document.addEventListener("click", (event) => {
    if (!adminMenu || !adminMenuButton) {
        return;
    }
    if (!adminMenu.hidden && !adminMenu.contains(event.target) && !adminMenuButton.contains(event.target)) {
        setAdminMenuOpen(false);
    }
});

document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") {
        return;
    }
    setAdminMenuOpen(false);
    if (changePasswordModal && !changePasswordModal.hidden) {
        closeChangePasswordModal();
    }
    // Escape 同时关闭订阅相关弹窗，保持三个管理弹窗的键盘行为一致。
    if (subscriptionModal && !subscriptionModal.hidden) {
        closeSubscriptionModal();
    }
    if (subscriptionEditModal && !subscriptionEditModal.hidden) {
        closeSubscriptionEditModal();
    }
    if (subscriptionDeleteModal && !subscriptionDeleteModal.hidden) {
        closeSubscriptionDeleteModal();
    }
});

const initialView = location.hash.replace(/^#/, "");
syncThemePreferenceUI(window.WebUiTheme?.getPreference?.() || "system");
activateView(initialView, !initialView || !views.has(initialView));
refreshRuntimeSummary();
setInterval(refreshRuntimeSummary, runtimeRefreshIntervalMs);

window.addEventListener("hashchange", () => {
    activateView(location.hash.replace(/^#/, ""));
});
