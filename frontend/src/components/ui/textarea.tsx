import * as React from "react"

import { cn } from "@/lib/utils"

/**
 * Textarea — 多行文本输入原子。
 * 对齐 input.tsx 视觉:surface-card-2 底 + focus border-accent + surface-card。
 * 用于策略描述、AI prompt、变更说明、回测参数备注等多行输入。
 */
function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return (
    <textarea
      data-slot="textarea"
      className={cn(
        "w-full min-w-0 rounded-md border border-transparent bg-surface-card-2 px-md py-2 text-body text-text-primary transition-[border,background] outline-none placeholder:text-text-muted disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50",
        "focus:border-accent focus:bg-surface-card",
        "aria-invalid:border-destructive aria-invalid:ring-destructive/20",
        className,
      )}
      {...props}
    />
  )
}

export { Textarea }
