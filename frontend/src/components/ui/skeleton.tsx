import { cn } from "@/lib/utils"

function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      // shimmer 有方向感("数据正在流入"),reduced-motion 由全局兜底降为静帧
      className={cn("shimmer-bg rounded-md bg-surface-card-2", className)}
      {...props}
    />
  )
}

export { Skeleton }
