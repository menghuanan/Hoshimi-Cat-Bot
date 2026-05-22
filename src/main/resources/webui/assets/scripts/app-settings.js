/**
 * WebUI settings feature script owns config loading, payload building, tab rendering, and save flows.
 */

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
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认保存");
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
        if (settingsKey === "proxyConfig.proxy" && payload.proxyUpdateMode === "preserve") {
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
 * BiliConfig payload 从当前快照补齐非本分区字段，确保文件级 DTO 不会被局部表单清空。
 */
function buildBiliConfigSettingsPayload(sectionName, values, confirmationPassword = "") {
    const read = (key, fallback = "") => values[key] ?? fieldValue("biliConfig", key, fallback);
    const submittedProxies = settingsLines(values["proxyConfig.proxy"] || "");
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
        proxies: submittedProxies,
        proxyUpdateMode: submittedProxies.length > 0 ? "replace" : "preserve",
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
    const placeholder = field.placeholder ? ` placeholder="${escapeHtml(field.placeholder)}"` : "";
    return `<label class="settings-field${wide}">
        <span>${escapeHtml(field.label)}</span>
        <textarea name="${escapeHtml(field.key)}" rows="${field.rows || 4}"${placeholder}>${escapeHtml(value)}</textarea>
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
 * 敏感列表只允许新值写入，默认空白提交由后端解释为保留当前配置。
 */
function renderWriteOnlyListTextarea(field) {
    return renderSettingTextarea({
        ...field,
        value: "",
        rows: field.rows || 5,
        placeholder: field.placeholder || "留空则保留原值；每行一个新值",
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
        ${renderWriteOnlyListTextarea({file: "biliConfig", key: "proxyConfig.proxy", label: "代理地址", wide: true})}
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

