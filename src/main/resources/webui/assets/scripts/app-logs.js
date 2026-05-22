/**
 * WebUI log feature script owns log source loading, filtering, refreshing, clearing, and file download behavior.
 */

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
    if (!await requestCenteredConfirmation("确认清空当前日志来源的内容？")) {
        return;
    }
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认清空日志");
    if (!confirmationPassword) {
        setLogStatus("已取消清空");
        return;
    }
    const response = await fetch(`/api/logs/${encodeURIComponent(sourceId)}/clear`, {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({confirmationPassword}),
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

