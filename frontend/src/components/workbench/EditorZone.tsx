import { MonacoEditor } from '@/components/MonacoEditor'
import { TabBar } from './TabBar'
import { BottomControlBar } from './BottomControlBar'
import { Button } from '@/components/ui/button'
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
      <div className="flex items-center gap-md border-b border-border bg-surface-card px-lg py-sm">
        <Button
          variant="outline"
          onClick={onSave}
          disabled={!codeId || isSaving}
        >
          {isSaving ? '保存中…' : '保存'}
        </Button>
        <Button
          variant={isPublished ? 'ghost' : 'default'}
          onClick={onPublish}
          disabled={!codeId || isPublished || isPublishing}
        >
          {isPublished ? '已发布' : isPublishing ? '发布中…' : '发布'}
        </Button>
      </div>
      <div className="min-h-0 flex-1">
        <MonacoEditor value={source} onChange={onSourceChange} />
      </div>
      <BottomControlBar
        strategyId={strategyId}
        isPublished={isPublished}
        onRunBacktest={onRunBacktest}
        onRunLive={onRunLive}
        isSubmitting={isSubmitting}
      />
    </div>
  )
}
