import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { BiliQrLoginModal } from './BiliQrLoginModal'

describe('BiliQrLoginModal', () => {
  /** 提交态已经越过二维码租约，不得继续展示误导性的剩余秒数。 */
  it('does not show qr lease countdown while committing', () => {
    render(
      <BiliQrLoginModal
        open
        loading={false}
        session={{
          sessionId: 'commit-session',
          phase: 'COMMITTING',
          expiresAtEpochMillis: Date.now() + 1_000,
          message: '正在保存登录凭据',
        }}
        error=""
        onClose={vi.fn()}
        onRetry={vi.fn()}
      />,
    )

    expect(screen.getAllByText('正在保存登录凭据')).not.toHaveLength(0)
    expect(screen.queryByText(/剩余 \d+ 秒/)).not.toBeInTheDocument()
  })
})
