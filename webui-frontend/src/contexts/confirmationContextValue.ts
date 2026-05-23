import { createContext } from 'react'

export type ConfirmationContextValue = {
  requestHighRiskConfirmation: (message: string) => Promise<string>
}

/**
 * 确认弹窗上下文值放在非组件文件，避免 Fast Refresh 将 provider 组件和共享对象混在一起。
 */
export const ConfirmationContext = createContext<ConfirmationContextValue | null>(null)
