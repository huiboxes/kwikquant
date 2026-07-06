import * as React from "react"

import { cn } from "@/lib/utils"

function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        // DESIGN.md §input: bg=surface-input, text=text-primary, rounded=full, body-sm
        // h-11(44px) 替代 shadcn 默认 h-9(36px),padding 加大可正常输入
        "h-11 w-full min-w-0 rounded-full border border-border bg-surface-input px-md py-2 text-body-sm text-text-primary shadow-xs transition-[color,box-shadow] outline-none selection:bg-primary selection:text-primary-foreground file:inline-flex file:h-9 file:border-0 file:bg-transparent file:font-medium file:text-text-primary placeholder:text-text-muted disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
        // DESIGN.md §input-focus-ring: accent-soft(铜色 soft)
        "focus-visible:border-accent-soft focus-visible:ring-[3px] focus-visible:ring-accent-soft/50",
        "aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40",
        className,
      )}
      {...props}
    />
  )
}

export { Input }
