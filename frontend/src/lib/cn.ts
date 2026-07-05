import { clsx, type ClassValue } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

/**
 * className 合并:clsx 拼接 + tailwind-merge 去重(后写的 class 胜出)。
 * shadcn 组件标准 helper。
 *
 * DESIGN.md 自定义 token 注册(关键!):
 * tailwind-merge 默认不识别自定义 color/font 名,会把 `text-up`(颜色) 和 `text-caption`(字号)
 * 当同类冲突合并 → 丢一个。此处 extendTailwindMerge 把 DESIGN.md colors + fonts 注册进去,
 * 让 twMerge 正确区分 text-{color} / text-{size} / font-{family} / font-{weight}。
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      // DESIGN.md §colors + shadcn 映射层变量名(见 index.css @theme inline)
      color: [
        'primary',
        'onyx',
        'slate',
        'on-primary',
        'primary-foreground',
        'accent',
        'accent-deep',
        'accent-soft',
        'accent-warm',
        'on-accent',
        'accent-foreground',
        'up',
        'down',
        'warning',
        'warning-bg',
        'warning-text',
        'info',
        'text-primary',
        'text-secondary',
        'text-muted',
        'surface-canvas',
        'surface-card',
        'surface-card-2',
        'surface-input',
        'surface-hover',
        'border',
        'border-soft',
        'interactive-hover',
        'interactive-active',
        'interactive-selected',
        'interactive-disabled',
        'background',
        'foreground',
        'card',
        'card-foreground',
        'popover',
        'popover-foreground',
        'secondary',
        'secondary-foreground',
        'muted',
        'muted-foreground',
        'destructive',
        'destructive-foreground',
        'input',
        'ring',
      ],
    },
  },
})

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
