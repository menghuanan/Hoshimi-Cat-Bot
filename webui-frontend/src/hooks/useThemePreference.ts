import { useCallback, useEffect, useState } from 'react'

type ThemePreference = 'system' | 'light' | 'dark'

const themePreferenceStorageKey = 'dynamic_bot_webui_theme'
const themeClassNames = ['theme-system', 'theme-light', 'theme-dark']

/**
 * 登录页和壳层都能读取的 cookie 主题偏好，服务端渲染壳层时也能沿用同一模式。
 */
function readThemePreferenceCookie(): ThemePreference | null {
  if (typeof document === 'undefined') {
    return null
  }
  const entry = document.cookie.split(';').map((value) => value.trim()).find((value) => value.startsWith(`${themePreferenceStorageKey}=`))
  if (!entry) {
    return null
  }
  const value = entry.slice(themePreferenceStorageKey.length + 1)
  return value === 'light' || value === 'dark' || value === 'system' ? value : null
}

/**
 * 主题模式需要同步到 html 节点和 cookie，保证刷新前后壳层表现一致。
 */
function applyThemePreference(preference: ThemePreference) {
  document.documentElement.dataset.theme = preference
  document.documentElement.classList.remove(...themeClassNames)
  document.documentElement.classList.add(`theme-${preference}`)
  document.cookie = `${themePreferenceStorageKey}=${preference}; path=/; SameSite=Lax`
}

/**
 * 主题模式由前端本地状态管理，并即时写入 DOM、localStorage 和 cookie。
 */
export function useThemePreference() {
  const [preference, setPreference] = useState<ThemePreference>(() => {
    const cookiePreference = readThemePreferenceCookie()
    if (cookiePreference) {
      return cookiePreference
    }
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
