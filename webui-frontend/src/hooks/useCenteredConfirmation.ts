import { useConfirmationContext } from './useConfirmationContext'

/**
 * 仅需要普通确认的场景复用同一个 provider，但暴露更窄的 hook 语义。
 */
export function useCenteredConfirmation() {
  return useConfirmationContext().requestCenteredConfirmation
}
