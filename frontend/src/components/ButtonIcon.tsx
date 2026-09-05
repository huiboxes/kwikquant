/* eslint-disable react-refresh/only-export-components -- cva variants 非组件导出，react-refresh 不适用 */
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

/**
 * ButtonIcon — 纯图标按钮。
 *
 * 用于工具栏/卡片操作/列表行操作等只需图标的场景。
 * solid=品牌底白字(主操作图标钮),ghost=透明底(默认),copper=solid 别名(历史命名)。
 * rounded=full,size 44px(md)/36px(sm)/52px(lg),focus 走品牌软环。
 */
const buttonIconVariants = cva(
  'inline-flex items-center justify-center rounded-full transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft focus-visible:ring-offset-2 focus-visible:ring-offset-surface-canvas disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        solid: 'bg-primary text-on-accent hover:bg-accent-deep',
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
