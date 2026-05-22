/**
 * WebUI script entrypoint preserves the public /assets/app.js path while loading split plain-script assets in order.
 */
(async function loadWebUiScripts() {
    const scriptPaths = [
        "/assets/scripts/app-state.js",
        "/assets/scripts/app-utils.js",
        "/assets/scripts/app-subscriptions.js",
        "/assets/scripts/app-logs.js",
        "/assets/scripts/app-runtime.js",
        "/assets/scripts/app-dialogs.js",
        "/assets/scripts/app-settings.js",
        "/assets/scripts/app-navigation.js",
    ];

    for (const path of scriptPaths) {
        await new Promise((resolve, reject) => {
            const script = document.createElement("script");
            script.src = path;
            script.onload = resolve;
            script.onerror = () => reject(new Error(`WebUI script load failed: ${path}`));
            document.head.appendChild(script);
        });
    }
})();
