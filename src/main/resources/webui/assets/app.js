const runtimeSummary = document.getElementById("runtime-summary");
const runtimeStatus = document.getElementById("runtime-status");
const biliConfigForm = document.getElementById("bili-config-form");
const biliDataForm = document.getElementById("bili-data-form");
const botConfigForm = document.getElementById("bot-config-form");
const biliConfigStatus = document.getElementById("bili-config-status");
const biliDataStatus = document.getElementById("bili-data-status");
const botConfigStatus = document.getElementById("bot-config-status");
const logSourceSelect = document.getElementById("log-source-select");
const logTailSelect = document.getElementById("log-tail-select");
const refreshLogViewerButton = document.getElementById("refresh-log-viewer");
const logViewer = document.getElementById("log-viewer");
const logWindowMeta = document.getElementById("log-window-meta");
const logStatus = document.getElementById("log-status");
const biliConfigOverview = document.getElementById("bili-config-overview");
const biliDataOverview = document.getElementById("bili-data-overview");
const botConfigOverview = document.getElementById("bot-config-overview");
const reloadConfigActionButton = document.getElementById("reload-config-action");
const shutdownActionButton = document.getElementById("shutdown-action");
const requestRestartActionButton = document.getElementById("request-restart-action");
const actionStatus = document.getElementById("action-status");

const LOGS_BASE = "/api/logs/";
const LOG_REFRESH_INTERVAL_MS = 5000;
const snapshotState = {
    biliConfig: "",
    biliData: "",
    botConfig: "",
};
let logRefreshTimer = null;
let logRefreshInFlight = false;

/**
 * API 调用统一附带当前 token，确保运行态和配置数据都只来自受保护的 `/api/*` 响应。
 */
async function apiFetch(path, options = {}) {
    const token = sessionStorage.getItem("webuiToken");
    const headers = {
        Accept: "application/json",
        ...(options.headers || {}),
    };
    if (token) {
        headers.Authorization = `Bearer ${token}`;
    }
    return fetch(path, {
        ...options,
        headers,
    });
}

/**
 * 会话无效或仍需改密时直接回到登录页，避免开放管理壳的匿名访问。
 */
async function ensureAuthenticatedSession() {
    const response = await apiFetch("/api/auth/session");
    if (!response.ok) {
        window.location.replace("/login");
        return null;
    }
    const session = await response.json();
    if (!session.authenticated || session.mustChangePassword) {
        window.location.replace("/login");
        return null;
    }
    return session;
}

async function readJsonResponse(path, options = {}) {
    const response = await apiFetch(path, options);
    const payload = await response.json().catch(() => ({}));
    return {
        response,
        payload,
    };
}

/**
 * 统一把保存结果和动作结果显示到状态区，明确展示 validation/conflict/effect/recommendedAction。
 */
function describeResult(payload, fallbackStatus) {
    const message = payload.message || `HTTP ${fallbackStatus}`;
    const outcome = payload.outcome ? `outcome=${payload.outcome}` : "";
    const effect = payload.effectiveLevel ? `effect=${payload.effectiveLevel}` : "";
    const recommendedAction = payload.recommendedAction ? `next=${payload.recommendedAction}` : "";
    const operatorHint = payload.operatorHint ? `hint=${payload.operatorHint}` : "";
    const gracefulStopScheduled = typeof payload.gracefulStopScheduled === "boolean"
        ? `stop=${payload.gracefulStopScheduled ? "scheduled" : "not-scheduled"}`
        : "";
    const restartExpected = typeof payload.restartExpected === "boolean"
        ? `restart=${payload.restartExpected ? "expected" : "manual"}`
        : "";
    const validationErrors = Array.isArray(payload.validationErrors) && payload.validationErrors.length > 0
        ? `errors=${payload.validationErrors.join(", ")}`
        : "";
    return [message, outcome, effect, recommendedAction, operatorHint, gracefulStopScheduled, restartExpected, validationErrors]
        .filter((part) => part)
        .join(" | ");
}

/**
 * 读取字段 DTO 时按 key 建索引，避免前端把后端对象结构写死成位置依赖。
 */
function fieldMap(fileDto) {
    return Object.fromEntries(fileDto.fields.map((field) => [field.key, field]));
}

/**
 * 配置 overview 统一按字段列表渲染，让三份配置文件的完整只读树都能直接在页面上看见。
 */
function renderConfigOverview(container, fileDto) {
    container.innerHTML = "";
    if (!fileDto || !Array.isArray(fileDto.fields) || fileDto.fields.length === 0) {
        container.textContent = "No configuration fields available.";
        return;
    }

    fileDto.fields.forEach((field) => {
        const row = document.createElement("article");
        row.className = "config-overview-row";

        const head = document.createElement("div");
        head.className = "config-overview-head";

        const label = document.createElement("span");
        label.className = "config-overview-label";
        label.textContent = field.label || field.key;

        const capability = document.createElement("span");
        capability.className = `config-capability capability-${String(field.capability || "READ_ONLY").toLowerCase()}`;
        capability.textContent = field.capability || "READ_ONLY";

        const key = document.createElement("code");
        key.className = "config-overview-key";
        key.textContent = field.key;

        const value = document.createElement("pre");
        value.className = "config-overview-value";
        value.textContent = field.value || "(empty)";

        head.append(label, capability);
        row.append(head, key, value);
        container.append(row);
    });
}

function setSecretInputValue(input, field) {
    input.value = "";
    input.placeholder = field && field.value ? "留空表示保留现有值" : "当前未设置";
}

/**
 * 日志窗口会为前端提供固定的尾长预设，方便本地快速切换最近窗口而不暴露任意分页。
 */
function renderTailOptions(availableTailLines, selectedTailLines) {
    const options = Array.isArray(availableTailLines) && availableTailLines.length > 0
        ? availableTailLines
        : [20, 50, 200];
    logTailSelect.innerHTML = "";
    options.forEach((tailLines) => {
        const option = document.createElement("option");
        option.value = String(tailLines);
        option.textContent = `${tailLines} lines`;
        logTailSelect.append(option);
    });
    const selectedValue = options.includes(selectedTailLines) ? String(selectedTailLines) : String(options[0]);
    logTailSelect.value = selectedValue;
}

/**
 * 日志窗口元数据统一显示 source、tail、hasMore 和缺失状态，减少本地调试时的猜测成本。
 */
function describeLogWindow(payload) {
    const sourceMissing = payload.sourceMissing ? "sourceMissing=true" : "sourceMissing=false";
    const hasMore = typeof payload.hasMore === "boolean" ? `hasMore=${payload.hasMore}` : "";
    const requestedTailLines = typeof payload.requestedTailLines === "number" ? `tail=${payload.requestedTailLines}` : "";
    const lineCount = typeof payload.lineCount === "number" ? `lines=${payload.lineCount}` : "";
    const availableTailLines = Array.isArray(payload.availableTailLines) && payload.availableTailLines.length > 0
        ? `available=${payload.availableTailLines.join(", ")}`
        : "";
    const lastModifiedEpochMillis = typeof payload.lastModifiedEpochMillis === "number"
        ? `lastModified=${payload.lastModifiedEpochMillis}`
        : "";
    return [requestedTailLines, lineCount, hasMore, sourceMissing, availableTailLines, lastModifiedEpochMillis]
        .filter((part) => part)
        .join(" | ");
}

/**
 * 日志自动刷新只允许单实例运行，避免轮询和手动刷新互相叠加。
 */
function stopLogAutoRefresh() {
    if (logRefreshTimer !== null) {
        clearInterval(logRefreshTimer);
        logRefreshTimer = null;
    }
}

/**
 * 只要日志源可用就启动轮询刷新，让尾部窗口跟随最新日志变化。
 */
function startLogAutoRefresh() {
    stopLogAutoRefresh();
    logRefreshTimer = setInterval(() => {
        if (document.visibilityState === "hidden") {
            return;
        }
        refreshLogViewer().catch((error) => {
            logStatus.textContent = `failed to refresh log: ${error.message}`;
        });
    }, LOG_REFRESH_INTERVAL_MS);
}

async function loadRuntimeSummary() {
    const { response, payload } = await readJsonResponse("/api/runtime/summary");
    if (!response.ok) {
        runtimeStatus.textContent = describeResult(payload, response.status);
        return;
    }
    runtimeSummary.textContent = JSON.stringify(payload, null, 2);
    runtimeStatus.textContent = `state=${payload.lifecycleState} | platformReady=${payload.platformReady} | restartRequestMode=${payload.restartRequestMode}`;
}

async function loadBiliConfigPanel() {
    const { response, payload } = await readJsonResponse("/api/config/bili-config");
    if (!response.ok) {
        biliConfigStatus.textContent = describeResult(payload, response.status);
        return;
    }
    const fields = fieldMap(payload);
    snapshotState.biliConfig = payload.snapshotToken;
    document.getElementById("bili-config-admin-contact").value = fields["adminContact"]?.value || "";
    document.getElementById("bili-config-baidu-app-id").value = fields["translateConfig.baidu.APP_ID"]?.value || "";
    document.getElementById("bili-config-debug-mode").checked = fields["enableConfig.debugMode"]?.value === "true";
    setSecretInputValue(document.getElementById("bili-config-cookie"), fields["accountConfig.cookie"]);
    setSecretInputValue(document.getElementById("bili-config-baidu-security-key"), fields["translateConfig.baidu.SECURITY_KEY"]);
    biliConfigStatus.textContent = `snapshot=${payload.snapshotToken}`;
    renderConfigOverview(biliConfigOverview, payload);
}

async function loadBiliDataPanel() {
    const { response, payload } = await readJsonResponse("/api/config/bili-data");
    if (!response.ok) {
        biliDataStatus.textContent = describeResult(payload, response.status);
        return;
    }
    const fields = fieldMap(payload);
    snapshotState.biliData = payload.snapshotToken;
    document.getElementById("bili-data-blacklist").value = fields["linkParseBlacklistContacts"]?.value || "";
    biliDataStatus.textContent = `snapshot=${payload.snapshotToken} | subscriptions=${fields["dynamic.count"]?.value || "0"} | groups=${fields["group.count"]?.value || "0"}`;
    renderConfigOverview(biliDataOverview, payload);
}

async function loadBotConfigPanel() {
    const { response, payload } = await readJsonResponse("/api/config/bot");
    if (!response.ok) {
        botConfigStatus.textContent = describeResult(payload, response.status);
        return;
    }
    const fields = fieldMap(payload);
    snapshotState.botConfig = payload.snapshotToken;
    document.getElementById("bot-config-platform-type").value = fields["platform.type"]?.value || "";
    document.getElementById("bot-config-adapter").value = fields["platform.adapter"]?.value || "";
    document.getElementById("bot-config-host").value = fields["platform.onebot11.host"]?.value || "";
    document.getElementById("bot-config-port").value = fields["platform.onebot11.port"]?.value || "";
    setSecretInputValue(document.getElementById("bot-config-token"), fields["platform.onebot11.token"]);
    botConfigStatus.textContent = `snapshot=${payload.snapshotToken}`;
    renderConfigOverview(botConfigOverview, payload);
}

async function saveBiliConfig(event) {
    event.preventDefault();
    const { response, payload } = await readJsonResponse("/api/config/bili-config", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            snapshotToken: snapshotState.biliConfig,
            adminContact: document.getElementById("bili-config-admin-contact").value.trim(),
            cookie: document.getElementById("bili-config-cookie").value,
            baiduAppId: document.getElementById("bili-config-baidu-app-id").value.trim(),
            baiduSecurityKey: document.getElementById("bili-config-baidu-security-key").value,
            debugMode: document.getElementById("bili-config-debug-mode").checked,
            confirmationPassword: document.getElementById("bili-config-confirmation").value,
        }),
    });
    biliConfigStatus.textContent = describeResult(payload, response.status);
    if (response.ok) {
        document.getElementById("bili-config-confirmation").value = "";
        await loadBiliConfigPanel();
    }
}

async function saveBiliData(event) {
    event.preventDefault();
    const contacts = document.getElementById("bili-data-blacklist").value
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line);
    const { response, payload } = await readJsonResponse("/api/config/bili-data", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            snapshotToken: snapshotState.biliData,
            linkParseBlacklistContacts: contacts,
            confirmationPassword: document.getElementById("bili-data-confirmation").value,
        }),
    });
    biliDataStatus.textContent = describeResult(payload, response.status);
    if (response.ok) {
        document.getElementById("bili-data-confirmation").value = "";
        await loadBiliDataPanel();
    }
}

async function saveBotConfig(event) {
    event.preventDefault();
    const { response, payload } = await readJsonResponse("/api/config/bot", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            snapshotToken: snapshotState.botConfig,
            platformType: document.getElementById("bot-config-platform-type").value.trim(),
            adapter: document.getElementById("bot-config-adapter").value.trim(),
            oneBot11Host: document.getElementById("bot-config-host").value.trim(),
            oneBot11Port: Number(document.getElementById("bot-config-port").value),
            oneBot11Token: document.getElementById("bot-config-token").value,
            confirmationPassword: document.getElementById("bot-config-confirmation").value,
        }),
    });
    botConfigStatus.textContent = describeResult(payload, response.status);
    if (response.ok) {
        document.getElementById("bot-config-confirmation").value = "";
        await loadBotConfigPanel();
    }
}

async function loadLogSources() {
    const { response, payload } = await readJsonResponse("/api/logs/sources");
    if (!response.ok) {
        logStatus.textContent = describeResult(payload, response.status);
        stopLogAutoRefresh();
        return;
    }
    logSourceSelect.innerHTML = "";
    payload.sources.forEach((source) => {
        const option = document.createElement("option");
        option.value = source.id;
        option.textContent = source.title;
        logSourceSelect.append(option);
    });
    if (payload.sources.length > 0) {
        await refreshLogViewer();
        startLogAutoRefresh();
    } else {
        stopLogAutoRefresh();
        logViewer.textContent = "";
        logWindowMeta.textContent = "No source metadata is currently available.";
        logStatus.textContent = "No allowed log sources are available.";
    }
}

async function refreshLogViewer() {
    if (logRefreshInFlight) {
        return;
    }
    logRefreshInFlight = true;
    const sourceId = logSourceSelect.value;
    if (!sourceId) {
        logStatus.textContent = "Choose a log source first.";
        logRefreshInFlight = false;
        return;
    }
    try {
        const requestedTail = Number(logTailSelect.value || 200);
        const { response, payload } = await readJsonResponse(`${LOGS_BASE}${encodeURIComponent(sourceId)}?tail=${requestedTail}`);
        if (!response.ok) {
            logStatus.textContent = describeResult(payload, response.status);
            return;
        }
        renderTailOptions(payload.availableTailLines, payload.requestedTailLines);
        logViewer.textContent = payload.text || "(empty log window)";
        logViewer.scrollTop = logViewer.scrollHeight;
        logWindowMeta.textContent = describeLogWindow(payload);
        logStatus.textContent = payload.sourceMissing
            ? "Selected log file is not currently available."
            : "Log window loaded.";
    } finally {
        logRefreshInFlight = false;
    }
}

async function runAction(path, statusTarget) {
    const { response, payload } = await readJsonResponse(path, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
            body: JSON.stringify({
                confirmationPassword: document.getElementById("action-confirmation").value,
            }),
        });
    statusTarget.textContent = describeResult(payload, response.status);
    if (response.ok) {
        document.getElementById("action-confirmation").value = "";
    }
}

async function loadManagementShell() {
    const session = await ensureAuthenticatedSession();
    if (!session) {
        return;
    }

    await Promise.all([
        loadRuntimeSummary(),
        loadBiliConfigPanel(),
        loadBiliDataPanel(),
        loadBotConfigPanel(),
        loadLogSources(),
    ]);
}

biliConfigForm.addEventListener("submit", saveBiliConfig);
biliDataForm.addEventListener("submit", saveBiliData);
botConfigForm.addEventListener("submit", saveBotConfig);
refreshLogViewerButton.addEventListener("click", () => {
    refreshLogViewer().catch((error) => {
        logStatus.textContent = `failed to refresh log: ${error.message}`;
    });
});
logSourceSelect.addEventListener("change", () => {
    refreshLogViewer().catch((error) => {
        logStatus.textContent = `failed to refresh log: ${error.message}`;
    });
});
logTailSelect.addEventListener("change", () => {
    refreshLogViewer().catch((error) => {
        logStatus.textContent = `failed to refresh log: ${error.message}`;
    });
});
reloadConfigActionButton.addEventListener("click", () => {
    runAction("/api/actions/reload-config", actionStatus).catch((error) => {
        actionStatus.textContent = `reload failed: ${error.message}`;
    });
});
shutdownActionButton.addEventListener("click", () => {
    runAction("/api/actions/shutdown", actionStatus).catch((error) => {
        actionStatus.textContent = `shutdown failed: ${error.message}`;
    });
});
requestRestartActionButton.addEventListener("click", () => {
    runAction("/api/actions/request-restart", actionStatus).catch((error) => {
        actionStatus.textContent = `restart request failed: ${error.message}`;
    });
});

loadManagementShell().catch((error) => {
    runtimeSummary.textContent = `failed to load shell: ${error.message}`;
    runtimeStatus.textContent = "management shell load failed";
});
