import * as React from "react"

import { cn } from "@/lib/utils"

function Input({ className, type, ...props }: React.ComponentProps<"input">) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        // 照原型 .kq-input:surface-card-2 底 + 10px 圆角 + focus border-accent
        "h-11 w-full min-w-0 rounded-md border border-transparent bg-surface-card-2 px-md py-2 text-body text-text-primary transition-[border,background] outline-none selection:bg-accent selection:text-on-accent file:inline-flex file:h-9 file:border-0 file:bg-transparent file:font-medium file:text-text-primary placeholder:text-text-muted disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
        "focus:border-accent focus:bg-surface-card",
        "aria-invalid:border-destructive aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40",
        className,
      )}
      {...props}
    />
  )
}

export { Input }
