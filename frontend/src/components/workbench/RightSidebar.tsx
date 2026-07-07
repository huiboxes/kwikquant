import { useState } from 'react'
import {
  BarChart3,
  MessageSquare,
  PanelLeftClose,
  PanelLeftOpen,
} from 'lucide-react'
import { BacktestResultPanel } from './BacktestResultPanel'
import { AISidebar } from '@/components/AISidebar'

/**
 * RightSidebar — 右栏 340px(spec §2.4)。
 *
 * 展开堆叠 BacktestResultPanel + AISidebar(AI flex-1 弹性)。
 * 折叠 48px 竖条,点图标唤抽屉;localStorage 持久化。
 * 响应式(<1024 提示)在 Task 13 StrategyWorkbench 处理。
 */
export function RightSidebar({
  strategyId,
  taskId,
}: {
  strategyId: number
  taskId: number | null
}) {
  const [collapsed, setCollapsed] = useState<boolean>(
    () => localStorage.getItem('kwikquant.rightsidebar.collapsed') === 'true',
  )
  const [activeDrawer, setActiveDrawer] = useState<'backtest' | 'ai' | null>(
    null,
  )

  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    localStorage.setItem('kwikquant.rightsidebar.collapsed', String(next))
  }

  if (collapsed) {
    return (
      <aside className="flex w-[48px] flex-col items-center gap-md border-l border-border bg-surface-card py-md">
        <button
          type="button"
          onClick={() => setActiveDrawer('backtest')}
          aria-label="回测结果"
          className="flex h-[40px] w-[40px] items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text-primary"
        >
          <BarChart3 className="h-[20px] w-[20px]" />
        </button>
        <button
          type="button"
          onClick={() => setActiveDrawer('ai')}
          aria-label="AI 助手"
          className="flex h-[40px] w-[40px] items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text-primary"
        >
          <MessageSquare className="h-[20px] w-[20px]" />
        </button>
        <div className="flex-1" />
        <button
          type="button"
          onClick={toggle}
          aria-label="展开右栏"
          className="flex h-[40px] w-[40px] items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text-primary"
        >
          <PanelLeftOpen className="h-[20px] w-[20px]" />
        </button>
        {activeDrawer && (
          <div className="fixed right-[48px] top-0 h-screen w-[340px] border-l border-border bg-surface-card shadow-float">
            {activeDrawer === 'backtest' ? (
              <BacktestResultPanel taskId={taskId} />
            ) : (
              <AISidebar />
            )}
          </div>
        )}
      </aside>
    )
  }

  return (
    <aside className="flex w-[340px] flex-col gap-md border-l border-border bg-surface-canvas p-md">
      <BacktestResultPanel taskId={taskId} />
      <div className="min-h-0 flex-1">
        <AISidebar />
      </div>
      <button
        type="button"
        onClick={toggle}
        aria-label="收起右栏"
        className="flex h-[40px] w-[40px] items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text-primary"
      >
        <PanelLeftClose className="h-[20px] w-[20px]" />
      </button>
    </aside>
  )
}
