/**
 * WebUI runtime feature script owns dashboard summary formatting, runtime card rendering, and refresh requests.
 */

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

