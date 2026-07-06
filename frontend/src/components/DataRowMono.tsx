import { clsx } from 'clsx'

/**
 * DataRowMono — 键值对数据行(spec §5 共享组件 #3,DESIGN.md §data-row-mono)。
 *
 * 用于展示数字/金额/指标:系统等宽 font-mono + tnum + zero feature(等宽数字对齐)。
 * DESIGN.md §data-row-mono: typography=mono, textColor 按数据动态 up/down/primary。
 *
 * 金额红线:value 一律 string(decimal.js 来源),不在此组件做运算。
 */
export interface DataRowMonoProps extends React.ComponentProps<'div'> {
  label: string
  value: string
  /** 值的语义色:up(涨绿)/down(跌红)/primary(默认主文字)/muted(副) */
  tone?: 'up' | 'down' | 'primary' | 'muted'
}

const toneClass: Record<NonNullable<DataRowMonoProps['tone']>, string> = {
  up: 'text-up',
  down: 'text-down',
  primary: 'text-text-primary',
  muted: 'text-text-muted',
}

export function DataRowMono({ label, value, tone = 'primary', className, ...props }: DataRowMonoProps) {
  return (
    <div
      className={clsx('flex items-center justify-between gap-md py-sm', className)}
      {...props}
    >
      <span className="font-body text-body-sm text-text-secondary">{label}</span>
      <span
        className={clsx('font-mono-num text-mono tabular-nums', toneClass[tone])}
      >
        {value}
      </span>
    </div>
  )
}
