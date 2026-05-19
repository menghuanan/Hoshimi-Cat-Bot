const apiStatus = document.getElementById("api-status");

/**
 * 只探活占位健康接口，避免在基础阶段引入额外前端状态管理复杂度。
 */
async function loadHealthStatus() {
    try {
        const response = await fetch("/api/health", {
            headers: {
                Accept: "application/json",
            },
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const payload = await response.json();
        apiStatus.textContent = `${payload.status} (${payload.phase})`;
    } catch (error) {
        apiStatus.textContent = `unavailable (${error.message})`;
    }
}

loadHealthStatus();
