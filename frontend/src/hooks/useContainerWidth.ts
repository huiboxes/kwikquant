import { useLayoutEffect, useRef, useState } from 'react'

/**
 * useContainerWidth — ResizeObserver 测量容器宽度,供手写 SVG 图表自适应。
 *
 * 返回 { ref, width }:width 为容器 clientWidth(px),首帧/未挂载/jsdom 为 0。
 * 调用方用 `width || fallback` 兜底(测试环境无真实布局,落到显式 width prop)。
 * useLayoutEffect 在绘制前同步量宽,避免"首帧按 fallback 宽渲染、下帧收缩"的闪烁。
 * 对齐 KlineChart autoSize(ResizeObserver)语义,避免裸 SVG 固定宽度在移动端撑破布局。
 */
export function useContainerWidth<T extends HTMLElement>() {
  const ref = useRef<T | null>(null)
  const [width, setWidth] = useState(0)

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    // 首帧同步量宽(useLayoutEffect 在 paint 前执行,无闪烁)
    setWidth(el.clientWidth)
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width
      if (w != null) setWidth(Math.round(w))
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  return { ref, width }
}
