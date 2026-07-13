import type { ReactNode } from 'react'

/**
 * SectionTitle — 页内分区标题(非 Card 头)。
 * icon + title(17px/700)+ sub(12px/muted) 左,right 插槽右,flex 两端对齐 + flex-wrap。
 * 对齐原型 ui.jsx SectionTitle,用于 Card 内分区标题(运行中策略/实时动态/组合权益曲线 等)。
 */
export function SectionTitle({
  title,
  sub,
  right,
  icon,
  className,
}: {
  title: ReactNode
  sub?: ReactNode
  right?: ReactNode
  icon?: ReactNode
  className?: string
}) {
  return (
    <div className={`mb-3.5 flex flex-wrap items-end justify-between gap-2.5 ${className ?? ''}`}>
      <div>
        <div className="flex items-center gap-2">
          {icon}
          <div className="text-h3 font-bold tracking-[-0.01em] text-text-primary">{title}</div>
        </div>
        {sub && <div className="mt-0.5 text-body-sm text-text-muted">{sub}</div>}
      </div>
      {right}
    </div>
  )
}
