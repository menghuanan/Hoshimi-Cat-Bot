import { useCallback, useEffect, useState } from 'react'

type ThemePreference = 'system' | 'light' | 'dark'

const themePreferenceStorageKey = 'dynamic_bot_webui_theme'
const themeClassNames = ['theme-system', 'theme-light', 'theme-dark']

/**
 * 主题偏好需要同步到 html 节点和 cookie，保证刷新前后壳层表现一致。
 */
function applyThemePreference(preference: ThemePreference) {
  document.documentElement.dataset.theme = preference
  document.documentElement.classList.remove(...themeClassNames)
  document.documentElement.classList.add(`theme-${preference}`)
  document.cookie = `${themePreferenceStorageKey}=${preference}; path=/; SameSite=Lax`
}

/**
 * 主题偏好由前端本地状态管理，并即时写入 DOM、localStorage 和 cookie。
 */
export function useThemePreference() {
  const [preference, setPreference] = useState<ThemePreference>(() => {
    const stored = window.localStorage.getItem(themePreferenceStorageKey)
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      return stored
    }
    return 'system'
  })

  useEffect(() => {
    applyThemePreference(preference)
  }, [preference])

  const updatePreference = useCallback((nextPreference: ThemePreference) => {
    setPreference(nextPreference)
    window.localStorage.setItem(themePreferenceStorageKey, nextPreference)
    applyThemePreference(nextPreference)
  }, [])

  return {preference, setPreference: updatePreference}
}
