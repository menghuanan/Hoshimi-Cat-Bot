const pageTitle = document.getElementById("page-title");
const contentArea = document.querySelector(".content");
const navItems = Array.from(document.querySelectorAll("[data-nav-target]"));
const views = new Map(
    Array.from(document.querySelectorAll("[data-view]"), (view) => [view.dataset.view, view]),
);

const viewTitles = {
    home: "首页",
    settings: "系统配置",
    features: "功能开关",
    subscriptions: "订阅管理",
    logs: "日志",
};

const defaultView = "home";

/**
 * 静态壳只负责在可见页面之间切换，不触碰任何 API 或业务状态。
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

const initialView = location.hash.replace(/^#/, "");
activateView(initialView, !initialView || !views.has(initialView));

window.addEventListener("hashchange", () => {
    activateView(location.hash.replace(/^#/, ""));
});
