/**
 * WebUI navigation bootstraps event bindings after all plain-script feature files have populated the shared scope.
 */

/**
 * 首页统计卡快捷入口先同步设置子页，再切换 hash，确保从首页直达对应配置分类。
 */
function navigateMetricShortcut(button) {
    const targetView = button.dataset.metricNav || defaultView;
    const settingsTab = button.dataset.settingsTabTarget || "";
    if (settingsTab) {
        activateSettingsTab(settingsTab);
    }
    if (location.hash.replace(/^#/, "") === targetView) {
        activateView(targetView);
        return;
    }
    location.hash = targetView;
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
    } else if (targetName === "subscriptions") {
        stopLogAutoRefresh();
        refreshSubscriptions().catch((error) => setSubscriptionError(error.message || "订阅加载失败"));
    } else if (targetName === "settings") {
        stopLogAutoRefresh();
        loadSettingsFiles().then(renderSettingsActiveTab).catch((error) => setSettingsStatus(error.message || "配置加载失败"));
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

metricNavButtons.forEach((button) => {
    button.addEventListener("click", () => {
        navigateMetricShortcut(button);
    });
});

settingsTabButtons.forEach((button) => {
    button.addEventListener("click", () => {
        activateSettingsTab(button.dataset.settingsTab || "");
    });
});

settingsPanels.forEach((panel) => {
    panel.addEventListener("submit", (event) => {
        const form = event.target.closest("[data-settings-form]");
        if (!form) {
            return;
        }
        event.preventDefault();
        saveSettingsSection(form.dataset.settingsForm).catch((error) => {
            setSettingsStatus(error.message || "保存失败");
        });
    });
    panel.addEventListener("click", (event) => {
        if (!event.target.closest("[data-settings-refresh]")) {
            return;
        }
        loadSettingsFiles(true).then(renderSettingsActiveTab).catch((error) => setSettingsStatus(error.message || "刷新失败"));
    });
    panel.addEventListener("change", (event) => {
        if (event.target.name === "platform.type") {
            const field = settingsState.files.botConfig.fieldsByKey.get("platform.type");
            if (field) {
                settingsState.files.botConfig.fieldsByKey.set("platform.type", {...field, value: event.target.value});
            }
            renderIntegrationSettings();
        }
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

// 订阅筛选按钮只更新前端筛选态，避免每次切换标签都重新请求后端。
subscriptionFilterButtons.forEach((button) => {
    button.addEventListener("click", () => {
        subscriptionState.filter = button.dataset.subscriptionFilter || "all";
        renderSubscriptions();
    });
});

if (subscriptionSearchInput) {
    // 搜索框即时过滤当前快照，接口刷新时会保留用户输入的关键字。
    subscriptionSearchInput.addEventListener("input", () => {
        subscriptionState.search = subscriptionSearchInput.value || "";
        renderSubscriptions();
    });
}

if (addSubscriptionButton) {
    addSubscriptionButton.addEventListener("click", openSubscriptionModal);
}

if (closeSubscriptionModalButton) {
    closeSubscriptionModalButton.addEventListener("click", closeSubscriptionModal);
}

if (cancelSubscriptionModalButton) {
    cancelSubscriptionModalButton.addEventListener("click", closeSubscriptionModal);
}

if (subscriptionCreateType) {
    subscriptionCreateType.addEventListener("change", updateSubscriptionCreateFields);
}

if (subscriptionCreateForm) {
    // 表单提交只走新增订阅接口，校验错误留在弹窗底部反馈。
    subscriptionCreateForm.addEventListener("submit", (event) => {
        event.preventDefault();
        createSubscription().catch((error) => setSubscriptionModalStatus(error.message || "添加失败"));
    });
}

if (subscriptionList) {
    // 卡片按钮由列表容器代理，避免每次刷新后重新绑定全部按钮。
    subscriptionList.addEventListener("click", (event) => {
        const editButton = event.target.closest("[data-subscription-edit]");
        if (editButton) {
            openSubscriptionEditModal(editButton.dataset.subscriptionEdit || "");
            return;
        }
        const deleteButton = event.target.closest("[data-subscription-delete]");
        if (deleteButton) {
            openSubscriptionDeleteModal(deleteButton.dataset.subscriptionDelete || "");
        }
    });
}

if (closeSubscriptionEditButton) {
    closeSubscriptionEditButton.addEventListener("click", closeSubscriptionEditModal);
}

if (subscriptionEditModal) {
    // 编辑弹窗内部使用事件代理，列表刷新和表单切换后不需要重新绑定按钮。
    subscriptionEditModal.addEventListener("click", (event) => {
        const multiSelectTrigger = event.target.closest("[data-multi-select-trigger]");
        if (multiSelectTrigger) {
            toggleMultiSelect(multiSelectTrigger.closest("[data-multi-select]"));
            return;
        }
        if (!event.target.closest("[data-multi-select]")) {
            closeMultiSelectMenus();
        }
        const actionButton = event.target.closest("[data-edit-action]");
        if (actionButton) {
            const action = actionButton.dataset.editAction;
            const loaders = {
                filter: loadFilterEditor,
                template: loadTemplateEditor,
                atall: loadAtAllEditor,
                theme: loadThemeEditor,
            };
            loaders[action]?.().catch((error) => setSubscriptionEditStatus(error.message || "配置加载失败"));
            return;
        }
        if (event.target.closest("[data-editor-back]")) {
            renderSubscriptionEditMenu();
            setSubscriptionEditStatus("");
            return;
        }
        const addButton = event.target.closest("[data-config-add]");
        if (addButton) {
            openConfigForm(addButton.dataset.configAdd);
            return;
        }
        const editButton = event.target.closest("[data-config-edit]");
        if (editButton) {
            openConfigForm(editButton.dataset.configEdit, editButton.dataset.configKey || "");
            return;
        }
        const deleteButton = event.target.closest("[data-config-delete]");
        if (deleteButton) {
            deleteConfigItem(deleteButton.dataset.configDelete, deleteButton.dataset.configKey || "")
                .catch((error) => setSubscriptionEditStatus(error.message || "删除失败"));
            return;
        }
        const cancelButton = event.target.closest("[data-config-cancel]");
        if (cancelButton) {
            cancelConfigForm(cancelButton.dataset.configCancel);
        }
    });

    // 表单提交统一从当前 form 的 data-config-form 分发到对应保存逻辑。
    subscriptionEditModal.addEventListener("submit", (event) => {
        const form = event.target.closest("[data-config-form]");
        if (!form) {
            return;
        }
        event.preventDefault();
        const action = form.dataset.configForm;
        const submitters = {
            filter: submitFilterForm,
            template: submitTemplateForm,
            atall: submitAtAllForm,
            theme: submitThemeForm,
        };
        submitters[action]?.(form).catch((error) => setSubscriptionEditStatus(error.message || "保存失败"));
    });

    // 选择过滤方式和模板类型时同步切换字段显隐和说明文案，避免用户保存前看到旧说明。
    subscriptionEditModal.addEventListener("change", (event) => {
        const filterKind = event.target.closest("[data-filter-kind]");
        if (filterKind) {
            const showRegex = filterKind.value === "regex";
            subscriptionEditModal.querySelector("[data-filter-regex-field]")?.toggleAttribute("hidden", !showRegex);
            subscriptionEditModal.querySelector("[data-filter-type-field]")?.toggleAttribute("hidden", showRegex);
            return;
        }
        const templateType = event.target.closest("[data-template-type]");
        if (templateType) {
            const explain = subscriptionEditModal.querySelector("[data-template-explain]");
            if (explain) {
                explain.textContent = templateExplainText[templateType.value] || templateExplainText.dynamic;
            }
            return;
        }
        const randomToggle = event.target.closest("[data-template-random-toggle]");
        if (randomToggle) {
            toggleTemplateRandom(randomToggle);
            return;
        }
        const targetGroupOption = event.target.closest('input[name="targetGroups"]');
        if (targetGroupOption) {
            updateMultiSelectLabel(targetGroupOption.closest("[data-multi-select]"));
        }
    });
}

if (closeSubscriptionDeleteButton) {
    closeSubscriptionDeleteButton.addEventListener("click", closeSubscriptionDeleteModal);
}

if (cancelSubscriptionDeleteButton) {
    cancelSubscriptionDeleteButton.addEventListener("click", closeSubscriptionDeleteModal);
}

if (confirmSubscriptionDeleteButton) {
    // 删除确认按钮绑定真实删除请求，失败文案留在列表或弹窗状态区域展示。
    confirmSubscriptionDeleteButton.addEventListener("click", () => {
        confirmSubscriptionDelete().catch((error) => {
            if (subscriptionDeleteStatus) {
                subscriptionDeleteStatus.textContent = error.message || "删除失败";
            }
        });
    });
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

if (confirmRestartRequiredButton) {
    confirmRestartRequiredButton.addEventListener("click", closeRestartRequiredModal);
}

if (closeHighRiskConfirmButton) {
    closeHighRiskConfirmButton.addEventListener("click", () => closeHighRiskConfirmationModal());
}

if (cancelHighRiskConfirmButton) {
    cancelHighRiskConfirmButton.addEventListener("click", () => closeHighRiskConfirmationModal());
}

if (submitHighRiskConfirmButton) {
    // 确认按钮按当前模式返回布尔值或密码文本，空密码保留在弹窗内提示用户补全。
    submitHighRiskConfirmButton.addEventListener("click", () => {
        if (highRiskConfirmationState.mode === "password") {
            const password = highRiskConfirmPasswordInput?.value.trim() || "";
            if (!password) {
                if (highRiskConfirmStatus) {
                    highRiskConfirmStatus.textContent = "请输入当前密码";
                }
                highRiskConfirmPasswordInput?.focus();
                return;
            }
            closeHighRiskConfirmationModal(password);
            return;
        }
        closeHighRiskConfirmationModal(true);
    });
}

if (highRiskConfirmPasswordInput) {
    // 密码确认框支持 Enter 提交，保持和普通表单一致的键盘操作。
    highRiskConfirmPasswordInput.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            event.preventDefault();
            submitHighRiskConfirmButton?.click();
        }
    });
}

if (highRiskConfirmModal) {
    highRiskConfirmModal.addEventListener("click", (event) => {
        if (event.target === highRiskConfirmModal) {
            closeHighRiskConfirmationModal();
        }
    });
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
    if (highRiskConfirmModal && !highRiskConfirmModal.hidden) {
        closeHighRiskConfirmationModal();
    }
    // Escape 同时关闭订阅相关弹窗，保持三个管理弹窗的键盘行为一致。
    if (subscriptionModal && !subscriptionModal.hidden) {
        closeSubscriptionModal();
    }
    if (subscriptionEditModal && !subscriptionEditModal.hidden) {
        closeSubscriptionEditModal();
    }
    if (subscriptionDeleteModal && !subscriptionDeleteModal.hidden) {
        closeSubscriptionDeleteModal();
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
