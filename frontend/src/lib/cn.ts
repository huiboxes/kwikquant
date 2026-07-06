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
    // DESIGN.md §typography 自定义字号 scale(关键!):
    // 自定义 font-size 必须注册到 classGroups(不是 theme),tailwind-merge 才能正确区分
    // text-{size}(字号) vs text-{color}(颜色)。注册前 text-body-sm 被误判为 color,
    // 与 text-white/text-on-primary 等真 color 冲突 → 真 color 被删 → 按钮文字回退到
    // 继承色(黑底黑字)。注册后互不冲突。
    // 结构:'font-size' classGroup 的值是 [{ text: [...] }] 对象数组(text 是 fontSize 的子 group)。
    // ref: https://stackoverflow.com/questions/78185697
    classGroups: {
      'font-size': [
        {
          text: [
            'display',
            'h1',
            'h2',
            'h3',
            'body',
            'body-sm',
            'caption',
            'label-caps',
            'mono',
          ],
        },
      ],
    },
  },
})

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
