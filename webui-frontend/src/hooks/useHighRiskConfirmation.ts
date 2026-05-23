import { useConfirmationContext } from './useConfirmationContext'

/**
 * 高风险写操作的调用方只拿到密码确认 API，不直接接触 modal state。
 */
export function useHighRiskConfirmation() {
  const context = useConfirmationContext()
  return {
    requestHighRiskConfirmation: context.requestHighRiskConfirmation,
  }
}
