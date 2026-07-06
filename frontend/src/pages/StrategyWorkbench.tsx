import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { StageBreadcrumb } from '@/components/layout/StageBreadcrumb'
import { deriveStage } from '@/components/layout/stage'
import { MonacoEditor } from '@/components/MonacoEditor'
import { AISidebar } from '@/components/AISidebar'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { Button } from '@/components/ui/button'
import { useStrategyCodes, useStrategyCode } from '@/hooks/useStrategyCode'
import { usePublishCode } from '@/hooks/usePublishCode'
import { useUpdateDraftCode } from '@/hooks/useUpdateDraftCode'
import { STRATEGY_TEMPLATE } from '@/lib/strategyTemplate'
import { BacktestResultArea } from '@/components/BacktestResultArea'
import { useSubmitBacktest } from '@/hooks/useSubmitBacktest'
import type { BacktestSubmitInput } from '@/schemas/backtest'

/**
 * StrategyWorkbench — 策略工作区(spec §5 step 15-16 编码态 + step 24 回测态集成)。
 *
 * stage 从 URL ?stage= 派生(deep link 真相源,不进 Zustand):
 *  - code(默认):左 Monaco(flex-1) + 右 AISidebar(w-420)。publish 按钮 → canBacktest 解锁。
 *  - backtest:BacktestResultArea(直接显示结果,无表单)。&taskId=XX 深链 mount 时直接轮询。
 *
 * 流程(编码态):
 *  1. useStrategyCodes 找 DRAFT code(无 DRAFT 则用最新 PUBLISHED 或空模板)
 *  2. useStrategyCode 拉 sourceCode(契约 A,仅编码态请求)
 *  3. Monaco 填充,本地编辑 state + 自动保存 3s(useUpdateDraftCode PUT /:codeId)
 *  4. publish 按钮 → usePublishCode(POST /:codeId/publish)→ canBacktest 解锁
 */
export function StrategyWorkbench() {
  const { id } = useParams<{ id: string }>()
  const strategyId = id ? parseInt(id, 10) : null
  const [params, setParams] = useSearchParams()
  const stage = deriveStage(params)

  const taskIdParam = params.get('taskId')
  const parsedTaskId = taskIdParam ? parseInt(taskIdParam, 10) : Number.NaN
  const taskId = Number.isNaN(parsedTaskId) ? null : parsedTaskId

  const { data: codes, isLoading: codesLoading, error: codesError } = useStrategyCodes(strategyId)

  // 找 DRAFT code(无则首个 PUBLISHED,无则 null)
  const draftCode = useMemo(() => {
    if (!codes || codes.length === 0) return null
    return codes.find((c) => c.status === 'DRAFT') ?? codes[0]
  }, [codes])

  const codeId = draftCode?.id ?? null
  const isPublished = draftCode?.status === 'PUBLISHED'

  // 仅编码态拉 codeDetail(回测态不需要源码,省请求)
  const { data: codeDetail, isLoading: codeLoading, error: codeError } = useStrategyCode(
    strategyId,
    stage === 'code' ? codeId : null,
  )

  // 本地编辑 state + 自动保存(debounce 3s,仅 DRAFT 未发布时;按钮显示倒计时)
  const [localSource, setLocalSource] = useState<string | null>(null)
  const source = localSource ?? codeDetail?.sourceCode ?? STRATEGY_TEMPLATE

  const publish = usePublishCode()
  const save = useUpdateDraftCode()
  const retrySubmit = useSubmitBacktest()
  const [lastSubmit, setLastSubmit] = useState<BacktestSubmitInput | null>(null)
  const autoSaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const countdownTimer = useRef<ReturnType<typeof setInterval> | null>(null)
  const [saveCountdown, setSaveCountdown] = useState<number | null>(null)

  // 自动保存:localSource 变化后 3s 保存(仅 DRAFT 未发布 + 有改动);按钮显示 3→2→1 倒计时
  // setState 都在 setTimeout/interval callback 里(异步,避免 effect 同步 setState cascading)
  useEffect(() => {
    if (localSource === null || isPublished || !strategyId || !codeId) return
    if (localSource === codeDetail?.sourceCode) return
    if (autoSaveTimer.current) clearTimeout(autoSaveTimer.current)
    if (countdownTimer.current) clearInterval(countdownTimer.current)
    autoSaveTimer.current = setTimeout(() => {
      setSaveCountdown(3)
      countdownTimer.current = setInterval(() => {
        setSaveCountdown((c) => (c && c > 1 ? c - 1 : null))
      }, 1000)
      autoSaveTimer.current = setTimeout(() => {
        if (countdownTimer.current) clearInterval(countdownTimer.current)
        setSaveCountdown(null)
        save.mutate({ strategyId, codeId, sourceCode: localSource, changelog: 'auto save' })
      }, 3000)
    }, 0)
    return () => {
      if (autoSaveTimer.current) clearTimeout(autoSaveTimer.current)
      if (countdownTimer.current) clearInterval(countdownTimer.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [localSource, isPublished, strategyId, codeId, codeDetail?.sourceCode])

  if (strategyId === null) {
    return <ErrorState title="无效策略 ID" message="URL 中缺少策略 ID" />
  }

  // 提交回测成功 → 缓存 input(供 FAILED 重试 re-POST)+ 设 URL stage=backtest + taskId 深链
  const handleSubmitted = (newTaskId: number, input: BacktestSubmitInput) => {
    setLastSubmit(input)
    setParams((prev) => {
      const p = new URLSearchParams(prev)
      p.set('stage', 'backtest')
      p.set('taskId', `${newTaskId}`)
      return p
    })
  }
  // 重试 → 重新 POST /backtests(spec §4 line 146 "重新 POST,新 taskId"),用上次提交参数;
  // lastSubmit 缺失(用户深链直接进 FAILED 态,未走过提交)→ 清 taskId 回表单手改重提
  const handleRetry = () => {
    if (!lastSubmit) {
      setParams((prev) => {
        const p = new URLSearchParams(prev)
        p.delete('taskId')
        return p
      })
      return
    }
    retrySubmit.mutate(lastSubmit, {
      onSuccess: (task) => handleSubmitted(task.id, lastSubmit),
    })
  }

  // 跑回测:用默认参数(初始资金 10000 USDT, 最近 30 天, BTC/USDT BINANCE 1h)
  // 用户没改默认值 → window.confirm 确认;确认后 POST /backtests → 跳回测态看结果
  const handleRunBacktest = () => {
    if (!strategyId) return
    const endTime = new Date()
    const startTime = new Date(endTime.getTime() - 30 * 24 * 3600 * 1000)
    const input: BacktestSubmitInput = {
      strategyId,
      symbol: 'BTC/USDT',
      exchange: 'BINANCE',
      intervalValue: '1h',
      startTime: startTime.toISOString(),
      endTime: endTime.toISOString(),
      parameters: { initial_capital: 10000 },
    }
    if (
      !window.confirm(
        '用默认参数跑回测?\n\n初始资金 10000 USDT\nBTC/USDT · BINANCE · 1h\n最近 30 天',
      )
    )
      return
    retrySubmit.mutate(input, {
      onSuccess: (task) => handleSubmitted(task.id, input),
    })
  }

  // === 回测态 ===
  if (stage === 'backtest') {
    return (
      <div className="flex h-screen flex-col bg-surface-canvas text-text-primary">
        <header className="flex items-center justify-between gap-md border-b border-border px-xl py-md">
          <div>
            <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">
              Workbench
            </p>
            <h1 className="mt-sm font-display text-h2">
              策略 #{id} · 回测
            </h1>
          </div>
          <StageBreadcrumb canBacktest={isPublished} />
        </header>
        <main className="flex-1 overflow-auto p-lg">
          <div className="mx-auto max-w-5xl space-y-lg">
            {taskId === null ? (
              <div className="rounded-xl bg-surface-card p-2xl shadow-card text-center">
                <h3 className="font-display text-h3">还没有回测结果</h3>
                <p className="mt-md font-body text-body-sm text-text-secondary">
                  点"跑回测"用默认参数运行(初始资金 10000 USDT, 最近 30 天)。
                </p>
                <Button
                  className="mt-lg"
                  variant="default"
                  disabled={retrySubmit.isPending}
                  onClick={handleRunBacktest}
                >
                  {retrySubmit.isPending ? '提交中…' : '跑回测'}
                </Button>
              </div>
            ) : (
              <BacktestResultArea
                taskId={taskId}
                onRetry={handleRetry}
                isRetrying={retrySubmit.isPending}
              />
            )}
          </div>
        </main>
      </div>
    )
  }

  // === 编码态(默认) ===
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
          {!isPublished && codeId && (
            <Button
              variant="outline"
              disabled={save.isPending || localSource === null}
              onClick={() =>
                strategyId &&
                codeId &&
                save.mutate({ strategyId, codeId, sourceCode: source })
              }
            >
              {save.isPending ? '保存中…' : saveCountdown ? `${saveCountdown}s 后保存` : '保存'}
            </Button>
          )}
          <Button
            variant={isPublished ? 'ghost' : 'default'}
            disabled={publish.isPending || isPublished || !codeId}
            onClick={() => strategyId && codeId && publish.mutate({ strategyId, codeId })}
          >
            {isPublished ? '已发布' : publish.isPending ? '发布中…' : '发布'}
          </Button>
          {isPublished && (
            <Button
              variant="default"
              disabled={retrySubmit.isPending}
              onClick={handleRunBacktest}
            >
              {retrySubmit.isPending ? '提交中…' : '跑回测'}
            </Button>
          )}
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
