import { useConfirmationContext } from './useConfirmationContext'

/**
 * 高风险写操作的调用方只拿到两个 promise API，不直接接触 modal state。
 */
export function useHighRiskConfirmation() {
  const context = useConfirmationContext()
  return {
    requestCenteredConfirmation: context.requestCenteredConfirmation,
    requestHighRiskConfirmation: context.requestHighRiskConfirmation,
  }
}
