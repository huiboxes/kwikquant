import { MonacoEditor } from '@/components/MonacoEditor'
import { TabBar } from './TabBar'
import { BottomControlBar } from './BottomControlBar'
import type { DateRange } from 'react-day-picker'

interface EditorZoneProps {
  strategyId: number
  codeId: number | null
  source: string
  isPublished: boolean
  onSourceChange: (s: string) => void
  onSave: () => void
  onPublish: () => void
  isSaving?: boolean
  isPublishing?: boolean
  onRunBacktest: (params: {
    symbol: string
    interval: string
    range: DateRange | undefined
  }) => void
  onRunLive: (params: { symbol: string; interval: string }) => void
  isSubmitting: boolean
}

/**
 * EditorZone — 编辑器区容器(spec §2.3 主内容区)。
 *
 * 组装:TabBar(多策略 tab)+ 工具栏(保存/发布)+ MonacoEditor + BottomControlBar(交易对/interval/日期/Backtest/Run Live)。
 */
export function EditorZone({
  strategyId,
  codeId,
  source,
  isPublished,
  onSourceChange,
  onSave,
  onPublish,
  isSaving,
  isPublishing,
  onRunBacktest,
  onRunLive,
  isSubmitting,
}: EditorZoneProps) {
  return (
    <div className="flex h-full flex-col">
      <TabBar />
      <div className="min-h-0 flex-1">
        <MonacoEditor value={source} onChange={onSourceChange} />
      </div>
      <BottomControlBar
        strategyId={strategyId}
        codeId={codeId}
        isPublished={isPublished}
        onSave={onSave}
        onPublish={onPublish}
        isSaving={!!isSaving}
        isPublishing={!!isPublishing}
        onRunBacktest={onRunBacktest}
        onRunLive={onRunLive}
        isSubmitting={isSubmitting}
      />
    </div>
  )
}
