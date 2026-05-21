const pageTitle = document.getElementById("page-title");
const contentArea = document.querySelector(".content");
const navItems = Array.from(document.querySelectorAll("[data-nav-target]"));
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

settingsTabButtons.forEach((button) => {
    button.addEventListener("click", () => {
        activateSettingsTab(button.dataset.settingsTab || "");
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
