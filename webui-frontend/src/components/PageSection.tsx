import type { ReactNode } from 'react'

type PageSectionProps = {
  title: string
  description?: string
  actions?: ReactNode
  actionsPlacement?: 'top' | 'bottom'
  descriptionTone?: 'default' | 'danger'
  children: ReactNode
}

/**
 * 页面区块统一标题、说明和操作区布局，保证业务页密度一致，并允许少数页面把操作区放到标题下方。
 */
export function PageSection({title, description, actions, actionsPlacement = 'top', descriptionTone = 'default', children}: PageSectionProps) {
  const descriptionClassName = descriptionTone === 'danger'
    ? 'mt-1 break-words text-sm leading-6 text-rose-600'
    : 'mt-1 break-words text-sm leading-6 text-slate-600'

  return (
    <section className="min-w-0 space-y-4">
      {/* 标题区保留单行结构，只有显式要求时才把操作区移动到标题下方。 */}
      <div className={actionsPlacement === 'bottom' ? 'min-w-0' : 'flex min-w-0 flex-wrap items-start justify-between gap-3'}>
        <div className="min-w-0">
          <h3 className="text-base font-semibold text-slate-950">{title}</h3>
          {description ? <p className={descriptionClassName}>{description}</p> : null}
        </div>
        {actions && actionsPlacement !== 'bottom' ? <div className="flex min-w-0 flex-wrap items-center gap-2">{actions}</div> : null}
      </div>
      {actions && actionsPlacement === 'bottom' ? <div className="flex min-w-0 flex-wrap items-center justify-start gap-2">{actions}</div> : null}
      {children}
    </section>
  )
}
