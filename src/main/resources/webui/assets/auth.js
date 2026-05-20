const loginForm = document.getElementById("login-form");
const changePasswordForm = document.getElementById("change-password-form");
const authStatus = document.getElementById("auth-status");
const loginPasswordInput = document.getElementById("login-password");
const currentPasswordInput = document.getElementById("current-password");
const newPasswordInput = document.getElementById("new-password");

/**
 * 统一保存 token，供后续 `/api/*` 请求使用，同时让服务端 cookie 负责页面入口门禁。
 */
function persistToken(token) {
    if (token) {
        sessionStorage.setItem("webuiToken", token);
    }
}

/**
 * 认证流程结束后统一清理输入框，避免旧密码长期停留在页面上。
 */
function clearSensitiveInputs() {
    loginPasswordInput.value = "";
    newPasswordInput.value = "";
}

/**
 * 登录响应若要求强制改密，则切换到改密表单；否则直接进入主壳页。
 */
function applyLoginState(response, currentPassword) {
    persistToken(response.token);
    if (response.mustChangePassword) {
        currentPasswordInput.value = currentPassword;
        loginForm.classList.add("hidden");
        changePasswordForm.classList.remove("hidden");
        authStatus.textContent = "Password change required before entering the shell.";
        return;
    }
    window.location.replace("/");
}

async function loginWithPassword(password) {
    const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Accept: "application/json",
        },
        body: JSON.stringify({ password }),
    });
    const payload = await response.json();
    if (!response.ok) {
        throw new Error(payload.message || `HTTP ${response.status}`);
    }
    applyLoginState(payload, password);
    clearSensitiveInputs();
}

/**
 * 登录页加载时先探测现有会话，避免已登录用户重复停留在登录态。
 */
async function restoreSession() {
    const token = sessionStorage.getItem("webuiToken");
    const headers = {
        Accept: "application/json",
    };
    if (token) {
        headers.Authorization = `Bearer ${token}`;
    }
    const response = await fetch("/api/auth/session", { headers });
    if (!response.ok) {
        // 会话探测失败时立即清掉本地旧 token，避免登录页重复带着失效状态发起请求。
        sessionStorage.removeItem("webuiToken");
        return;
    }
    const session = await response.json();
    if (!session.authenticated) {
        // 服务端明确判定未认证时也同步清理本地 token，确保后续登录走干净状态。
        sessionStorage.removeItem("webuiToken");
        return;
    }
    if (session.mustChangePassword) {
        loginForm.classList.add("hidden");
        changePasswordForm.classList.remove("hidden");
        authStatus.textContent = "Password change required before entering the shell.";
        return;
    }
    window.location.replace("/");
}

loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
        const password = loginPasswordInput.value;
        await loginWithPassword(password);
    } catch (error) {
        authStatus.textContent = `Login failed: ${error.message}`;
    }
});

changePasswordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
        const token = sessionStorage.getItem("webuiToken");
        const response = await fetch("/api/auth/change-password", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/json",
                Authorization: token ? `Bearer ${token}` : "",
            },
            body: JSON.stringify({
                currentPassword: currentPasswordInput.value,
                newPassword: newPasswordInput.value,
            }),
        });
        const payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.message || `HTTP ${response.status}`);
        }
        sessionStorage.removeItem("webuiToken");
        authStatus.textContent = "Password changed. Logging in again...";
        await loginWithPassword(newPasswordInput.value);
        currentPasswordInput.value = "";
    } catch (error) {
        authStatus.textContent = `Password change failed: ${error.message}`;
    }
});

restoreSession().catch((error) => {
    authStatus.textContent = `Session check failed: ${error.message}`;
});
