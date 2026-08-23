import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Slot } from "radix-ui"

import { cn } from "@/lib/utils"

const buttonVariants = cva(
  "inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-pill text-sm font-medium whitespace-nowrap transition-all outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        // 契约 button-primary:暖橙 pill + hover 加深下沉;阴影体系只保留 card/pop 两层
        default:
          "bg-accent text-on-accent font-semibold hover:bg-accent-deep hover:-translate-y-px",
        destructive:
          "bg-destructive text-on-accent font-semibold hover:opacity-90 hover:-translate-y-px",
        outline:
          "border border-border bg-transparent text-text-primary hover:bg-surface-card-2 hover:border-text-muted",
        secondary:
          "border border-border bg-transparent text-text-primary hover:bg-surface-card-2",
        ghost:
          "border border-border bg-transparent text-text-primary hover:bg-surface-card-2 hover:border-text-muted",
        link: "text-accent underline-offset-4 hover:underline",
      },
      size: {
        // 契约主 CTA 40px 触达;sm=32px 留给表格等密集场景
        default: "h-10 gap-2 px-md text-body-sm [&_svg:not([class*='size-'])]:size-4",
        xs: "h-7 gap-1 px-sm text-caption [&_svg:not([class*='size-'])]:size-3",
        sm: "h-8 gap-1.5 px-base text-body-sm [&_svg:not([class*='size-'])]:size-4",
        lg: "h-11 gap-2 px-md text-body [&_svg:not([class*='size-'])]:size-5",
        icon: "size-10 rounded-full",
        "icon-xs": "size-7 rounded-full [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-8 rounded-full",
        "icon-lg": "size-11 rounded-full",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

function Button({
  className,
  variant = "default",
  size = "default",
  asChild = false,
  ...props
}: React.ComponentProps<"button"> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
  }) {
  const Comp = asChild ? Slot.Root : "button"

  return (
    <Comp
      data-slot="button"
      data-variant={variant}
      data-size={size}
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  )
}

export { Button, buttonVariants }
