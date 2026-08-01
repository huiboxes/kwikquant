import { MessageSquare, FlaskConical } from 'lucide-react'
import type { StrategyDetailDto } from '@/api/strategy'
import type { EditorCodeRef } from '@/hooks/useAssistantChat'
import { SessionPanel } from './SessionPanel'
import { BacktestPanel } from './BacktestPanel'

export type RightTab = 'session' | 'backtest'

export interface BacktestProgress {
  processed: number
  total: number
}

interface RightPanelProps {
  strategy: StrategyDetailDto | null
  version: number | null
  /** 编辑器实时 code ref(父组件 StrategyPage 管,编辑器 onChange 写 ref.current;传 SessionPanel,spec §4.2 m3)。 */
  editorCodeRef?: EditorCodeRef
  activeTab: RightTab
  onTabChange: (tab: RightTab) => void
  /** 回测进行中:回测 tab 显示进度态,父 WS 完成后清 false 自动显结果。 */
  running: boolean
  /** 回测进度(worker 逐 bar 上报,WS RUNNING 携带;null = 无进度数据显旋转 Loader)。 */
  progress?: BacktestProgress | null
  /** 全屏态(会话窗口铺满主区,代码编辑器让出空间)。 */
  fullscreen?: boolean
  /** 切换全屏(Bug3)。 */
  onToggleFullscreen?: () => void
}

/**
 * RightPanel — 右侧 tab 面板(用户要求:默认会话窗口,回测 tab 常驻)。
 *
 * IA 决策(2026-07-24):原型右侧是单"回测结果"卡,用户要求改默认会话(AI 对话)+ 回测 tab。
 * 两 tab 常驻(回测结果随时可看),回测提交时父 auto-switch 到回测 tab 显进度;会话为默认。
 * 不做条件出现(回测 tab 时隐时显)= 交互抖动;常驻 + auto-switch 更稳。
 *
 * 决策:useAssistantChat 唯一在 SessionPanel 调用(两处各调 hook 独立 state 不同步),
 * 故原 AiFab Sheet 对话已移除,FAB 简化为切到会话 tab。
 */
export function RightPanel({
  strategy,
  version,
  editorCodeRef,
  activeTab,
  onTabChange,
  running,
  progress,
  fullscreen,
  onToggleFullscreen,
}: RightPanelProps) {
  const tabs: { key: RightTab; label: string; icon: typeof MessageSquare }[] = [
    { key: 'session', label: '会话', icon: MessageSquare },
    { key: 'backtest', label: '回测', icon: FlaskConical },
  ]

  return (
    <div
      className={`${fullscreen ? 'flex w-full flex-1' : 'hidden w-[340px] shrink-0 lg:flex'} flex-col overflow-hidden bg-surface-card-2`}
    >
      {/* Tab bar */}
      <div className="flex gap-xxs bg-surface-card-2 px-xxs pt-xxs">
        {tabs.map((t) => {
          const active = activeTab === t.key
          const Icon = t.icon
          return (
            <button
              key={t.key}
              type="button"
              onClick={() => onTabChange(t.key)}
              className={`relative flex items-center gap-xxs rounded-t-lg px-sm py-xs text-caption font-semibold transition-colors ${
                active
                  ? 'bg-surface-card text-text-primary'
                  : 'text-text-muted hover:text-text-primary hover:bg-surface-card/60'
              }`}
            >
              <Icon className="size-3.5" aria-hidden />
              {t.label}
              {/* 回测 tab running 时显脉冲点 */}
              {t.key === 'backtest' && running && (
                <span className="size-1.5 animate-pulse rounded-full bg-accent" aria-hidden />
              )}
              {active && (
                <span className="absolute inset-x-0 bottom-0 h-[2px] rounded-full bg-accent" />
              )}
            </button>
          )
        })}
      </div>

      {/* Tab content(flex-1 填充;两面板各自 m-xxs + rounded-xl bg-surface-card) */}
      <div className="flex min-h-0 flex-1 flex-col">
        {activeTab === 'session' ? (
          <SessionPanel
            strategy={strategy}
            version={version}
            editorCodeRef={editorCodeRef}
            fullscreen={fullscreen}
            onToggleFullscreen={onToggleFullscreen}
          />
        ) : (
          <BacktestPanel strategyId={strategy?.id} running={running} progress={progress} />
        )}
      </div>
    </div>
  )
}
