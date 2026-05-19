const runtimeSummary = document.getElementById("runtime-summary");
const biliConfigBlock = document.getElementById("bili-config");
const biliDataBlock = document.getElementById("bili-data");
const botConfigBlock = document.getElementById("bot-config");

/**
 * API 调用统一附带当前 token，确保运行态和配置数据都只来自受保护的 `/api/*` 响应。
 */
async function apiFetch(path) {
    const token = sessionStorage.getItem("webuiToken");
    const headers = {
        Accept: "application/json",
    };
    if (token) {
        headers.Authorization = `Bearer ${token}`;
    }
    return fetch(path, { headers });
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

/**
 * 统一把 DTO 响应格式化到只读面板里，保持前端完全由 API 驱动。
 */
function renderJson(target, payload) {
    target.textContent = JSON.stringify(payload, null, 2);
}

async function loadShell() {
    const session = await ensureAuthenticatedSession();
    if (!session) {
        return;
    }

    const [runtime, biliConfig, biliData, botConfig] = await Promise.all([
        apiFetch("/api/runtime/summary"),
        apiFetch("/api/config/bili-config"),
        apiFetch("/api/config/bili-data"),
        apiFetch("/api/config/bot"),
    ]);

    renderJson(runtimeSummary, await runtime.json());
    renderJson(biliConfigBlock, await biliConfig.json());
    renderJson(biliDataBlock, await biliData.json());
    renderJson(botConfigBlock, await botConfig.json());
}

loadShell().catch((error) => {
    runtimeSummary.textContent = `failed to load shell: ${error.message}`;
});
