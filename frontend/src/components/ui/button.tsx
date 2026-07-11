import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { Slot } from "radix-ui"

import { cn } from "@/lib/utils"

const buttonVariants = cva(
  "inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md text-sm font-medium whitespace-nowrap transition-all outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        // 照原型 .kq-btn-primary:暖橙底 + 10px 圆角(base rounded-md)+ hover 下沉 + brand-glow
        default:
          "bg-accent text-on-accent font-semibold hover:bg-accent-deep hover:-translate-y-px hover:shadow-glow",
        destructive:
          "bg-destructive text-on-accent font-semibold hover:opacity-90 hover:-translate-y-px",
        // 照原型 .kq-btn-ghost:透明 + hairline 边 + 10px + hover surface-card-2
        outline:
          "border border-border bg-transparent text-text-primary hover:bg-surface-card-2 hover:border-text-muted",
        secondary:
          "border border-border bg-transparent text-text-primary hover:bg-surface-card-2",
        ghost:
          "border border-border bg-transparent text-text-primary hover:bg-surface-card-2 hover:border-text-muted",
        link: "text-accent underline-offset-4 hover:underline",
      },
      size: {
        default: "h-9 gap-2 px-base text-body-sm [&_svg:not([class*='size-'])]:size-4",
        xs: "h-7 gap-1 px-sm text-caption [&_svg:not([class*='size-'])]:size-3",
        sm: "h-8 gap-1.5 px-base text-body-sm [&_svg:not([class*='size-'])]:size-4",
        lg: "h-11 gap-2 px-md text-body [&_svg:not([class*='size-'])]:size-5",
        icon: "size-9",
        "icon-xs": "size-7 rounded-md [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-8",
        "icon-lg": "size-11",
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
