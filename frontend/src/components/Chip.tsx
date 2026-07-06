/* eslint-disable react-refresh/only-export-components -- cva variants 非组件导出,react-refresh 不适用 */
import { cva, type VariantProps } from 'class-variance-authority'
import { clsx } from 'clsx'

/**
 * Chip — 通用标签/徽章(spec §5 共享组件 #1)。
 *
 * 三种用途:
 *  - 状态徽章(6 色):up/down/warning/info/neutral/accent — 涨跌/警告/信息/中性/铜强调
 *  - exchange tag:交易所标签(如 "BINANCE"),用 neutral 色 + 系统等宽 font-mono
 *  - 多选标签:filter chip,带可选 onClose
 *
 * DESIGN.md token:bg-{语义色}/15 + text-{语义色},rounded-full,caption 字号。
 * 6 色映射 DESIGN.md §colors:up=Babu / down=Signal Down / warning=Warning / info=Info / neutral=surface-card-2 / accent=Copper。
 */
const chipVariants = cva(
  'inline-flex items-center gap-1 rounded-full font-medium whitespace-nowrap',
  {
    variants: {
      color: {
        up: 'bg-up/15 text-up',
        down: 'bg-down/15 text-down',
        warning: 'bg-warning-bg text-warning-text',
        info: 'bg-info/15 text-info',
        neutral: 'bg-surface-card-2 text-text-secondary',
        accent: 'bg-accent/15 text-accent',
      },
      size: {
        sm: 'px-sm py-[2px] text-caption',
        md: 'px-md py-1 text-body-sm',
      },
    },
    defaultVariants: { color: 'neutral', size: 'sm' },
  },
)

export interface ChipProps
  extends Omit<React.ComponentProps<'span'>, 'color'>,
    VariantProps<typeof chipVariants> {
  label: string
  onClose?: () => void
}

export function Chip({ label, color, size, onClose, className, ...props }: ChipProps) {
  return (
    <span className={clsx(chipVariants({ color, size }), className)} {...props}>
      {label}
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          className="ml-0.5 inline-flex shrink-0 items-center rounded-full opacity-60 hover:opacity-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft"
          aria-label={`移除 ${label}`}
        >
          <svg
            viewBox="0 0 16 16"
            className="size-3"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            aria-hidden
          >
            <path d="M4 4l8 8M12 4l-8 8" />
          </svg>
        </button>
      )}
    </span>
  )
}

export { chipVariants }
