import { type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * 弹窗挂到 body，避免 fixed 遮罩被带 transform 的页面动画容器截断。
 */
export function ModalPortal({children}: {children: ReactNode}) {
  if (typeof document === 'undefined' || !document.body) {
    return <>{children}</>
  }
  return createPortal(children, document.body)
}
