"use client"

import * as React from "react"
import { Label as LabelPrimitive } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * Label — 表单标签原子。
 * 对齐脚手架 text-body-sm + text-secondary,关联 input/checkbox 等控件。
 * 用于表单字段标题、设置项标签等。
 */
function Label({
  className,
  ...props
}: React.ComponentProps<typeof LabelPrimitive.Root>) {
  return (
    <LabelPrimitive.Root
      data-slot="label"
      className={cn(
        "flex items-center gap-2 text-body-sm font-medium leading-none text-text-secondary peer-disabled:cursor-not-allowed peer-disabled:opacity-70 group-data-[disabled=true]:pointer-events-none",
        className,
      )}
      {...props}
    />
  )
}

export { Label }
