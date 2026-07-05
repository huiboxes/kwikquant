/* eslint-disable react-refresh/only-export-components -- cva variants 非组件导出,react-refresh 不适用 */
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

/**
 * ButtonIcon — 纯图标按钮(spec §5 共享组件 #2,DESIGN.md §button-icon)。
 *
 * 用于工具栏/卡片操作/列表行操作等只需图标的场景。
 * DESIGN.md §button-icon: bg=primary(默认)/ghost(hover surface-hover),text=accent(铜)/text-primary,
 *   rounded=full,size 44px(md)/36px(sm)/52px(lg)。focus 环用 accent-soft(铜色 soft)。
 *
 * 与 shadcn Button variant=icon 区别:ButtonIcon 是 copper-soft focus 环 + 三档 size,
 * 用于需要铜色强调的图标按钮(如侧边栏操作、AI 工具栏)。
 */
const buttonIconVariants = cva(
  'inline-flex items-center justify-center rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        solid: 'bg-primary text-accent hover:bg-onyx',
        ghost: 'text-text-secondary hover:bg-surface-hover hover:text-text-primary',
        copper: 'bg-accent text-on-accent hover:bg-accent-deep',
      },
      size: {
        sm: 'h-[36px] w-[36px] [&_svg]:size-4',
        md: 'h-[44px] w-[44px] [&_svg]:size-5',
        lg: 'h-[52px] w-[52px] [&_svg]:size-6',
      },
    },
    defaultVariants: { variant: 'ghost', size: 'md' },
  },
)

export interface ButtonIconProps
  extends React.ComponentProps<'button'>,
    VariantProps<typeof buttonIconVariants> {
  label: string
}

export function ButtonIcon({ label, variant, size, className, children, ...props }: ButtonIconProps) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      className={cn(buttonIconVariants({ variant, size }), className)}
      {...props}
    >
      {children}
    </button>
  )
}

export { buttonIconVariants }
