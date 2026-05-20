const pageTitle = document.getElementById("page-title");
const contentArea = document.querySelector(".content");
const navItems = Array.from(document.querySelectorAll("[data-nav-target]"));
const views = new Map(
    Array.from(document.querySelectorAll("[data-view]"), (view) => [view.dataset.view, view]),
);

const viewTitles = {
    home: "首页",
    settings: "系统配置",
    features: "功能开关",
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
let logRefreshTimer = null;
let logRequestSequence = 0;

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
});

const initialView = location.hash.replace(/^#/, "");
syncThemePreferenceUI(window.WebUiTheme?.getPreference?.() || "system");
activateView(initialView, !initialView || !views.has(initialView));
refreshRuntimeSummary();
setInterval(refreshRuntimeSummary, runtimeRefreshIntervalMs);

window.addEventListener("hashchange", () => {
    activateView(location.hash.replace(/^#/, ""));
});
