/**
 * WebUI dialog feature script owns account actions, confirmation modals, restart prompts, and logout flows.
 */

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
 * 高风险确认弹窗关闭时统一清理输入和 resolver，避免下一次打开继承旧密码或旧 Promise。
 */
function closeHighRiskConfirmationModal(value = highRiskConfirmationState.mode === "password" ? "" : false) {
    const resolver = highRiskConfirmationState.resolve;
    highRiskConfirmationState.resolve = null;
    highRiskConfirmationState.mode = "confirm";
    if (highRiskConfirmPasswordInput) {
        highRiskConfirmPasswordInput.value = "";
    }
    if (highRiskConfirmStatus) {
        highRiskConfirmStatus.textContent = "";
    }
    if (highRiskConfirmModal) {
        highRiskConfirmModal.hidden = true;
    }
    resolver?.(value);
}

/**
 * 通用居中确认窗口支持普通确认和密码确认两种模式，替代浏览器原生 confirm/prompt。
 */
function openHighRiskConfirmationModal(options = {}) {
    if (!highRiskConfirmModal) {
        return Promise.resolve(options.mode === "password" ? "" : false);
    }
    if (highRiskConfirmationState.resolve) {
        closeHighRiskConfirmationModal();
    }
    highRiskConfirmationState.mode = options.mode === "password" ? "password" : "confirm";
    if (highRiskConfirmTitle) {
        highRiskConfirmTitle.textContent = options.title || "确认操作";
    }
    if (highRiskConfirmMessage) {
        highRiskConfirmMessage.textContent = options.message || "请确认本次操作。";
    }
    if (submitHighRiskConfirmButton) {
        submitHighRiskConfirmButton.textContent = options.confirmText || "确认";
    }
    if (highRiskConfirmPasswordField) {
        highRiskConfirmPasswordField.hidden = highRiskConfirmationState.mode !== "password";
    }
    if (highRiskConfirmPasswordInput) {
        highRiskConfirmPasswordInput.value = "";
    }
    if (highRiskConfirmStatus) {
        highRiskConfirmStatus.textContent = "";
    }
    highRiskConfirmModal.hidden = false;
    if (highRiskConfirmationState.mode === "password") {
        highRiskConfirmPasswordInput?.focus();
    } else {
        submitHighRiskConfirmButton?.focus();
    }
    return new Promise((resolve) => {
        highRiskConfirmationState.resolve = resolve;
    });
}

/**
 * 普通确认操作返回布尔值，供清空日志这类无密码前置确认继续保持二段确认语义。
 */
async function requestCenteredConfirmation(message = "请确认本次操作") {
    return Boolean(await openHighRiskConfirmationModal({
        mode: "confirm",
        title: "确认操作",
        message,
        confirmText: "确认",
    }));
}

/**
 * 高风险写操作统一二次输入当前密码，后端会继续执行服务端确认和短期确认缓存。
 */
async function requestHighRiskConfirmation(message = "请输入 WebUI 密码确认操作") {
    const value = await openHighRiskConfirmationModal({
        mode: "password",
        title: "密码确认",
        message,
        confirmText: "确认",
    });
    return String(value || "").trim();
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

