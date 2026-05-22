/**
 * WebUI subscription feature script owns list filtering, subscription CRUD, config editors, and multi-select controls.
 */

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
async function buildSubscriptionCreatePayload() {
    const type = subscriptionCreateType?.value || "dynamic";
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认新增订阅");
    if (!confirmationPassword) {
        return null;
    }
    if (type === "group") {
        return {
            type,
            groupName: document.getElementById("subscription-create-group-name")?.value || "",
            uid: document.getElementById("subscription-create-group-uid")?.value || "",
            targetGroup: document.getElementById("subscription-create-group-target")?.value || "",
            confirmationPassword,
        };
    }
    if (type === "bangumi") {
        return {
            type,
            bangumiId: document.getElementById("subscription-create-bangumi-id")?.value || "",
            targetGroup: document.getElementById("subscription-create-bangumi-target")?.value || "",
            confirmationPassword,
        };
    }
    return {
        type,
        uid: document.getElementById("subscription-create-uid")?.value || "",
        targetGroup: document.getElementById("subscription-create-target")?.value || "",
        confirmationPassword,
    };
}

/**
 * 新增订阅走后端业务 facade，成功后刷新卡片列表以展示真实写入结果。
 */
async function createSubscription() {
    const payloadToSend = await buildSubscriptionCreatePayload();
    if (!payloadToSend) {
        setSubscriptionModalStatus("已取消添加");
        return;
    }
    const response = await fetch("/api/subscriptions", {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify(payloadToSend),
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
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认删除订阅");
    if (!confirmationPassword) {
        setSubscriptionError("已取消删除");
        return;
    }
    const response = await fetch(`/api/subscriptions/${encodeURIComponent(itemId)}`, {
        method: "DELETE",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({confirmationPassword}),
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
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认保存过滤器");
    if (!confirmationPassword) {
        setSubscriptionEditStatus("已取消保存");
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
            confirmationPassword,
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
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认保存模板");
    if (!confirmationPassword) {
        setSubscriptionEditStatus("已取消保存");
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
            confirmationPassword,
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
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认保存at全体");
    if (!confirmationPassword) {
        setSubscriptionEditStatus("已取消保存");
        return;
    }
    if (oldItem && oldItem.type !== form.elements.type.value) {
        await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl(`/atall/${encodeURIComponent(oldKey)}`), {
            method: "DELETE",
            headers: buildAuthHeaders(true),
            body: JSON.stringify({confirmationPassword}),
        });
    }
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/atall"), {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({type: form.elements.type.value, targetGroups, confirmationPassword}),
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
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认保存主题色");
    if (!confirmationPassword) {
        setSubscriptionEditStatus("已取消保存");
        return;
    }
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/theme"), {
        method: "POST",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({color, confirmationPassword}),
    });
    setSubscriptionEditStatus("主题色已保存", true);
    await refreshSubscriptions();
}

/**
 * 删除配置项根据当前类型调用对应接口，删除完成后刷新当前列表和订阅卡片。
 */
async function deleteConfigItem(action, key) {
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认删除配置项");
    if (!confirmationPassword) {
        setSubscriptionEditStatus("已取消删除");
        return;
    }
    const path = action === "filter" ? `/filters/${encodeURIComponent(key)}`
        : action === "template" ? `/templates/${encodeURIComponent(key)}`
            : `/atall/${encodeURIComponent(key)}`;
    await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl(path), {
        method: "DELETE",
        headers: buildAuthHeaders(true),
        body: JSON.stringify({confirmationPassword}),
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
    const confirmationPassword = await requestHighRiskConfirmation("请输入 WebUI 密码确认切换随机模板");
    if (!confirmationPassword) {
        checkbox.checked = !nextValue;
        setSubscriptionEditStatus("已取消保存");
        return;
    }
    try {
        await fetchSubscriptionConfigJson(subscriptionConfigBaseUrl("/templates/random"), {
            method: "POST",
            headers: buildAuthHeaders(true),
            body: JSON.stringify({enabled: nextValue, confirmationPassword}),
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

