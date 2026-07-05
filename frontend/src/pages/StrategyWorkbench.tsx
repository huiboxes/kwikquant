import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { StageBreadcrumb } from '@/components/layout/StageBreadcrumb'
import { MonacoEditor } from '@/components/MonacoEditor'
import { AISidebar } from '@/components/AISidebar'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { Button } from '@/components/ui/button'
import { useStrategyCodes, useStrategyCode } from '@/hooks/useStrategyCode'
import { usePublishCode } from '@/hooks/usePublishCode'
import { STRATEGY_TEMPLATE } from '@/lib/strategyTemplate'

/**
 * StrategyWorkbench — 策略工作区编码态(spec §5 step 15-16)。
 *
 * 流程:
 *  1. useStrategyCodes 找 DRAFT code(无 DRAFT 则用最新 PUBLISHED 或空模板)
 *  2. useStrategyCode 拉 sourceCode(契约 A)
 *  3. Monaco 填充,本地编辑 state(批 1a 无保存草稿端点,批 2 补 PUT /codes/:codeId)
 *  4. publish 按钮 → usePublishCode(POST /:codeId/publish)→ canBacktest 解锁
 *
 * 布局:左 Monaco(flex-1) + 右 AISidebar(w-[420px])。顶部 StageBreadcrumb。
 */
export function StrategyWorkbench() {
  const { id } = useParams<{ id: string }>()
  const strategyId = id ? parseInt(id, 10) : null

  const { data: codes, isLoading: codesLoading, error: codesError } = useStrategyCodes(strategyId)

  // 找 DRAFT code(无则首个 PUBLISHED,无则 null)
  const draftCode = useMemo(() => {
    if (!codes || codes.length === 0) return null
    return codes.find((c) => c.status === 'DRAFT') ?? codes[0]
  }, [codes])

  const codeId = draftCode?.id ?? null
  const { data: codeDetail, isLoading: codeLoading, error: codeError } = useStrategyCode(
    strategyId,
    codeId,
  )

  // 本地编辑 state(批 1a 不持久化,批 2 补保存)
  const [localSource, setLocalSource] = useState<string | null>(null)
  const source = localSource ?? codeDetail?.sourceCode ?? STRATEGY_TEMPLATE

  const publish = usePublishCode()
  const isPublished = draftCode?.status === 'PUBLISHED'

  return (
    <div className="flex h-screen flex-col bg-surface-canvas text-text-primary">
      <header className="flex items-center justify-between gap-md border-b border-border px-xl py-md">
        <div>
          <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">
            Workbench
          </p>
          <h1 className="mt-sm font-display text-h2">策略 #{id}</h1>
        </div>
        <div className="flex items-center gap-md">
          <Button
            variant={isPublished ? 'ghost' : 'default'}
            disabled={publish.isPending || isPublished || !codeId}
            onClick={() => strategyId && codeId && publish.mutate({ strategyId, codeId })}
          >
            {isPublished ? '已发布' : publish.isPending ? '发布中…' : '发布'}
          </Button>
          <StageBreadcrumb canBacktest={isPublished} />
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <main className="flex-1 overflow-auto p-lg">
          {codesLoading || codeLoading ? (
            <LoadingState label="加载代码…" />
          ) : codesError || codeError ? (
            <ErrorState
              title="代码加载失败"
              message={
                (codesError instanceof Error ? codesError.message : undefined) ??
                (codeError instanceof Error ? codeError.message : undefined)
              }
            />
          ) : (
            <div className="h-full rounded-lg border border-border bg-surface-card p-sm">
              <MonacoEditor value={source} onChange={setLocalSource} />
            </div>
          )}
        </main>

        <div className="w-[420px] shrink-0">
          <AISidebar />
        </div>
      </div>
    </div>
  )
}
