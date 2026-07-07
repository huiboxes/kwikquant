import { Link } from 'react-router-dom'
import { useWorkbenchTabs } from '@/hooks/useWorkbenchTabs'
import { useStrategyCodes, useStrategyCode } from '@/hooks/useStrategyCode'
import { usePublishCode } from '@/hooks/usePublishCode'
import { useUpdateDraftCode } from '@/hooks/useUpdateDraftCode'
import { useSubmitBacktest } from '@/hooks/useSubmitBacktest'
import { useWorkbenchTabsStore } from '@/stores/workbenchTabsStore'
import { EditorZone } from '@/components/workbench/EditorZone'
import { RightSidebar } from '@/components/workbench/RightSidebar'
import { STRATEGY_TEMPLATE } from '@/lib/strategyTemplate'
import type { BacktestSubmitInput } from '@/schemas/backtest'
import type { DateRange } from 'react-day-picker'

/**
 * StrategyWorkbench — IDE 风格一屏看全工作台(spec §1)。
 *
 * URL ?tabs=&active= 驱动多 tab(useWorkbenchTabs)。
 * 空态(tabs 空):引导去策略列表。
 * 有 tabs:WorkbenchForStrategy(active)→ grid[1fr_340px] = EditorZone + RightSidebar。
 */
export function StrategyWorkbench() {
  const { tabs, active } = useWorkbenchTabs()

  if (tabs.length === 0 || active === null) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-md">
        <p className="font-body text-body">从策略列表选一个策略开始</p>
        <Link
          to="/strategies"
          className="rounded-md bg-primary px-lg py-sm text-on-primary"
        >
          去策略列表
        </Link>
      </div>
    )
  }

  return <WorkbenchForStrategy key={active} strategyId={active} />
}

function WorkbenchForStrategy({ strategyId }: { strategyId: number }) {
  const { drafts, lastTaskIds, setDraft, setLastTaskId, clearDraft } =
    useWorkbenchTabsStore()
  const { data: codes } = useStrategyCodes(strategyId)
  const draftCode = codes?.find((c) => c.status === 'DRAFT') ?? codes?.[0]
  const codeId = draftCode?.id ?? null
  const isPublished = draftCode?.status === 'PUBLISHED'
  const { data: codeDetail } = useStrategyCode(strategyId, codeId)
  const source = drafts[strategyId] ?? codeDetail?.sourceCode ?? STRATEGY_TEMPLATE

  const publish = usePublishCode()
  const save = useUpdateDraftCode()
  const submit = useSubmitBacktest()

  const handleRunBacktest = (params: {
    symbol: string
    interval: string
    range: DateRange | undefined
  }) => {
    const endTime = params.range?.to ?? new Date()
    const startTime =
      params.range?.from ?? new Date(endTime.getTime() - 30 * 86400000)
    const input: BacktestSubmitInput = {
      strategyId,
      symbol: params.symbol,
      exchange: 'BINANCE',
      intervalValue: params.interval,
      startTime: startTime.toISOString(),
      endTime: endTime.toISOString(),
      parameters: { initial_capital: 10000 },
    }
    submit.mutate(input, {
      onSuccess: (task) => setLastTaskId(strategyId, task.id),
    })
  }
  const handleRunLive = (params: { symbol: string; interval: string }) => {
    // Run Live = 实盘下单,后续 wave(本 task 仅 stub)
    console.log('run live', strategyId, params)
  }

  const handleSave = () => {
    if (!codeId) return
    save.mutate(
      { strategyId, codeId, sourceCode: source, changelog: 'auto save' },
      { onSuccess: () => clearDraft(strategyId) },
    )
  }

  return (
    <>
      <div className="hidden h-full grid-cols-[1fr_340px] lg:grid">
        <EditorZone
          strategyId={strategyId}
          codeId={codeId}
          source={source}
          isPublished={isPublished}
          onSourceChange={(s) => setDraft(strategyId, s)}
          onSave={handleSave}
          onPublish={() => codeId && publish.mutate({ strategyId, codeId })}
          isSaving={save.isPending}
          isPublishing={publish.isPending}
          onRunBacktest={handleRunBacktest}
          onRunLive={handleRunLive}
          isSubmitting={submit.isPending}
        />
        <RightSidebar
          strategyId={strategyId}
          taskId={lastTaskIds[strategyId] ?? null}
        />
      </div>
      <div className="flex h-full items-center justify-center p-lg text-center lg:hidden">
        <p className="font-body text-body text-text-secondary">
          屏幕宽度不足,请使用桌面端(≥1024px)查看策略工作台
        </p>
      </div>
    </>
  )
}
