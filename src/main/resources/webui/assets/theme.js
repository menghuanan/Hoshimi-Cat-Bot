/**
 * WebUI theme helper只管理前端外观偏好，不触碰认证 token 或运行态数据。
 */
(function () {
    const themeCookieName = "dynamic_bot_webui_theme";
    const validPreferences = new Set(["dark", "light", "system"]);
    let systemThemeListener = null;
    let systemThemeQuery = null;

    /**
     * Cookie 读取只扫描同源页面可见的简单键值对，避免把主题偏好和认证信息耦合。
     */
    function readCookie(name) {
        const prefix = `${encodeURIComponent(name)}=`;
        const entries = document.cookie ? document.cookie.split(";") : [];
        for (const entry of entries) {
            const trimmed = entry.trim();
            if (trimmed.startsWith(prefix)) {
                return decodeURIComponent(trimmed.slice(prefix.length));
            }
        }
        return "";
    }

    /**
     * 主题偏好写回 cookie，保证登录页和主壳刷新后仍能沿用同一个选择。
     */
    function writeCookie(name, value) {
        document.cookie = `${encodeURIComponent(name)}=${encodeURIComponent(value)}; Max-Age=31536000; Path=/; SameSite=Lax`;
    }

    /**
     * 偏好值只接受深色、亮色和跟随系统三种输入，其余值统一降级到系统模式。
     */
    function normalizePreference(preference) {
        return validPreferences.has(preference) ? preference : "system";
    }

    /**
     * 跟随系统时只把系统暗色结果映射成实际主题，避免前端样式层直接依赖媒体查询。
     */
    function resolveThemePreference(preference) {
        const normalizedPreference = normalizePreference(preference);
        if (normalizedPreference === "dark" || normalizedPreference === "light") {
            return {
                preference: normalizedPreference,
                theme: normalizedPreference,
            };
        }

        const darkPreferred = window.matchMedia?.("(prefers-color-scheme: dark)")?.matches === true;
        return {
            preference: "system",
            theme: darkPreferred ? "dark" : "light",
        };
    }

    /**
     * 主题实际应用点只写到 documentElement，方便 head 里尽早生效并被整页 CSS 复用。
     */
    function applyResolvedTheme(resolved) {
        document.documentElement.dataset.theme = resolved.theme;
        document.documentElement.dataset.themePreference = resolved.preference;
    }

    /**
     * 系统主题监听只在“跟随系统”模式下挂载，切换到固定主题时立即解绑。
     */
    function syncSystemThemeWatcher(preference) {
        if (systemThemeQuery && systemThemeListener) {
            if (typeof systemThemeQuery.removeEventListener === "function") {
                systemThemeQuery.removeEventListener("change", systemThemeListener);
            } else if (typeof systemThemeQuery.removeListener === "function") {
                systemThemeQuery.removeListener(systemThemeListener);
            }
            systemThemeQuery = null;
            systemThemeListener = null;
        }

        if (preference !== "system" || typeof window.matchMedia !== "function") {
            return;
        }

        systemThemeQuery = window.matchMedia("(prefers-color-scheme: dark)");
        systemThemeListener = () => {
            applyThemePreference("system", false);
        };
        if (typeof systemThemeQuery.addEventListener === "function") {
            systemThemeQuery.addEventListener("change", systemThemeListener);
        } else if (typeof systemThemeQuery.addListener === "function") {
            systemThemeQuery.addListener(systemThemeListener);
        }
    }

    /**
     * 统一读取、解析和应用主题偏好；persist=false 时只刷新页面状态，不改写 cookie。
     */
    function applyThemePreference(preference, persist = true) {
        const resolved = resolveThemePreference(preference);
        applyResolvedTheme(resolved);
        syncSystemThemeWatcher(resolved.preference);
        if (persist) {
            writeCookie(themeCookieName, resolved.preference);
        }
        return resolved;
    }

    /**
     * 页面启动时先从 cookie 恢复偏好，再让 CSS 在首帧就看到正确主题。
     */
    const initialPreference = normalizePreference(readCookie(themeCookieName));
    applyThemePreference(initialPreference, false);

    window.WebUiTheme = {
        cookieName: themeCookieName,
        getPreference: () => normalizePreference(readCookie(themeCookieName)),
        getResolvedTheme: () => document.documentElement.dataset.theme || resolveThemePreference(readCookie(themeCookieName)).theme,
        getPreferenceLabel: (preference) => {
            const normalizedPreference = normalizePreference(preference);
            if (normalizedPreference === "dark") {
                return "深色";
            }
            if (normalizedPreference === "light") {
                return "亮色";
            }
            return "跟随系统";
        },
        applyThemePreference,
        setPreference: (preference) => applyThemePreference(preference, true),
    };
})();
