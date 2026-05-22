import type { ReactNode } from 'react'

type PageSectionProps = {
  title: string
  description?: string
  actions?: ReactNode
  children: ReactNode
}

/**
 * 页面区块统一标题、说明和操作区布局，保证业务页密度一致。
 */
export function PageSection({title, description, actions, children}: PageSectionProps) {
  return (
    <section className="space-y-4">
      <div className="flex min-w-0 flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-base font-semibold text-slate-950">{title}</h3>
          {description ? <p className="mt-1 break-words text-sm leading-6 text-slate-600">{description}</p> : null}
        </div>
        {actions ? <div className="flex min-w-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
      {children}
    </section>
  )
}
