"use client"

import * as React from "react"
import { Slider as SliderPrimitive } from "radix-ui"

import { cn } from "@/lib/utils"

/**
 * Slider — 滑块原子。
 * Range 走深色实心,轨道灰底;滑块是控件不是动作,不带品牌色。
 * 用于回测滑点/手续费、初始资金等数值区间选择。
 */
function Slider({
  className,
  ...props
}: React.ComponentProps<typeof SliderPrimitive.Root>) {
  return (
    <SliderPrimitive.Root
      data-slot="slider"
      className={cn(
        "relative flex w-full touch-none select-none items-center",
        className,
      )}
      {...props}
    >
      <SliderPrimitive.Track className="relative h-1.5 w-full grow overflow-hidden rounded-full bg-surface-3">
        <SliderPrimitive.Range className="absolute h-full bg-onyx" />
      </SliderPrimitive.Track>
      <SliderPrimitive.Thumb className="block size-4 rounded-full border-2 border-surface-card bg-onyx shadow-sm transition-transform outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50" />
    </SliderPrimitive.Root>
  )
}

export { Slider }
