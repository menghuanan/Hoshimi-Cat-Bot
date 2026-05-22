/**
 * WebUI utility helpers stay side-effect-light so feature scripts can reuse formatting, auth, and parsing behavior.
 */

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

