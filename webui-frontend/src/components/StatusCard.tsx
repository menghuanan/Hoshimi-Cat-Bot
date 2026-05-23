import type { ReactNode } from 'react'

type StatusCardProps = {
  label: string
  value: ReactNode
  tone?: 'emerald' | 'sky' | 'amber' | 'rose'
  detail?: ReactNode
}

const toneClasses = {
  emerald: 'border-l-emerald-500',
  sky: 'border-l-sky-500',
  amber: 'border-l-amber-500',
  rose: 'border-l-rose-500',
}

/**
 * 状态卡只展示简短指标，用左侧色条区分语义而不引入额外状态逻辑。
 */
export function StatusCard({label, value, tone = 'emerald', detail}: StatusCardProps) {
  return (
    <article className={`min-h-28 min-w-0 rounded-lg border border-slate-200 border-l-4 ${toneClasses[tone]} bg-white p-4 shadow-sm`}>
      <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</p>
      <div className="mt-3 min-w-0 break-words text-xl font-semibold leading-tight text-slate-950 sm:text-2xl">{value}</div>
      {detail ? <div className="mt-2 text-sm text-slate-600">{detail}</div> : null}
    </article>
  )
}
