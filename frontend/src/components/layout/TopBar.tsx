import { useWorkbenchTabs } from '@/hooks/useWorkbenchTabs'
import { useStrategy } from '@/hooks/useStrategy'
import { WsConnectionIndicator } from '@/components/WsConnectionIndicator'

/**
 * TopBar — 36px 顶栏。
 * 左:路径面包屑 kwikquant.io / workbench / {strategyName}.py(无 active 省略文件名)
 * 右:Live 标签(复用 WsConnectionIndicator STOMP 连接状态)。
 */
export function TopBar() {
  const { active } = useWorkbenchTabs()
  const { data: strategy } = useStrategy(active)
  const fileName = strategy ? `${strategy.name}.py` : null

  return (
    <header className="flex h-[36px] items-center justify-between border-b border-border bg-surface-canvas px-lg">
      <nav className="flex items-center gap-sm font-mono text-body-sm text-text-secondary">
        <span>kwikquant.io</span>
        <span className="text-text-muted">/</span>
        <span>workbench</span>
        {fileName && (
          <>
            <span className="text-text-muted">/</span>
            <span className="text-text-primary">{fileName}</span>
          </>
        )}
      </nav>
      <WsConnectionIndicator />
    </header>
  )
}
