import { WsConnectionIndicator } from '@/components/WsConnectionIndicator'

/**
 * TopBar — 36px 顶栏(布局壳)。
 * 左:brand 文本;右:WS 连接状态。
 * 面包屑逻辑重做时再设计(当前为占位,不绑业务 hook)。
 */
export function TopBar() {
  return (
    <header className="flex h-[36px] items-center justify-between border-b border-border bg-surface-canvas px-lg">
      <nav className="flex items-center gap-sm font-mono text-body-sm text-text-secondary">
        <span>kwikquant.io</span>
      </nav>
      <WsConnectionIndicator />
    </header>
  )
}
