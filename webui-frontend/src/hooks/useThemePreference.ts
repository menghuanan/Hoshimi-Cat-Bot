import { useCallback, useState } from 'react'

type ThemePreference = 'system' | 'light' | 'dark'

const themePreferenceStorageKey = 'dynamic_bot_webui_theme'

/**
 * 主题偏好先由前端本地状态管理，后续再和旧主题 helper 对接。
 */
export function useThemePreference() {
  const [preference, setPreference] = useState<ThemePreference>(() => {
    const stored = window.localStorage.getItem(themePreferenceStorageKey)
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      return stored
    }
    return 'system'
  })

  const updatePreference = useCallback((nextPreference: ThemePreference) => {
    setPreference(nextPreference)
    window.localStorage.setItem(themePreferenceStorageKey, nextPreference)
  }, [])

  return {preference, setPreference: updatePreference}
}
