"use client"

import * as React from "react"
import { Progress as ProgressPrimitive } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * Progress — 进度条原子。
 * Indicator 用 accent(品牌色),对齐脚手架交互色。
 * 用于回测 RUNNING 进度(如 bt-2205 progress=64)等异步任务进度展示。
 * value 通过 props.value 传入(0-100),Indicator 自动按百分比渲染宽度。
 */
function Progress({
  className,
  ...props
}: React.ComponentProps<typeof ProgressPrimitive.Root>) {
  return (
    <ProgressPrimitive.Root
      data-slot="progress"
      className={cn(
        "relative h-2 w-full overflow-hidden rounded-full bg-surface-card-2",
        className,
      )}
      {...props}
    >
      <ProgressPrimitive.Indicator className="h-full w-full flex-1 bg-accent transition-all" />
    </ProgressPrimitive.Root>
  )
}

export { Progress }
