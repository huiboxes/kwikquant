import { useEffect, useMemo, useRef, useState } from 'react'
import { Plus, Trash2, Loader2 } from 'lucide-react'
import { toast } from 'sonner'
import Editor from '@monaco-editor/react'
import { Chip } from '@/components/Chip'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ErrorState } from '@/components/ErrorState'
import { LoadingState } from '@/components/feedback/LoadingState'
import {
  useStrategies,
  useStrategyDetail,
  useStrategyCodes,
  useStrategyCodeDetail,
  usePublishCode,
  useReadyStrategy,
  useStartStrategy,
  usePauseStrategy,
  useStopStrategy,
  useUpdateCodeDraft,
  useCreateCodeDraft,
  useDeleteCodeDraft,
  useCreateStrategy,
  useDeleteStrategy,
} from '@/hooks/useStrategies'
import type { StrategyDetailDto, CreateStrategyRequest } from '@/api/strategy'

// 子组件
import { StrategySelector } from './strategy/StrategySelector'
import { WorkbenchTabBar } from './strategy/WorkbenchTabBar'
import { BottomControlBar } from './strategy/BottomControlBar'
import { BacktestPanel } from './strategy/BacktestPanel'
import { AiFab } from './strategy/AiFab'
import { PublishDialog } from './strategy/PublishDialog'
import { StartDialog } from './strategy/StartDialog'
import { VersionsDialog } from './strategy/VersionsDialog'
import { CreateStrategyDialog } from './strategy/CreateStrategyDialog'
import { FsmDialog } from './strategy/FsmDialog'
import { useSubmitBacktest } from '@/hooks/useBacktest'
import { backtestKeys } from '@/api/_queryKeys'
import { useQueryClient } from '@tanstack/react-query'
import type { SubmitBacktestRequest } from '@/api/backtest'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { useAuth } from '@/hooks/useAuth'

/**
 * StrategyPage — 策略工作台(IDE 布局,照原型 workbench.html)。
 *
 * 布局:Sub-header(策略选择器+操作按钮) + flex row(编辑器列+右侧回测面板) + AI FAB。
 * 编辑器列:TabBar → Meta line → Monaco(flex-1) → BottomControlBar。
 *
 * honest 差异(记 TD-032~038):
 *  - BottomControlBar 交易对/周期为视觉态(原生 select 不绑后端,TD-039)
 *  - 日期范围占位 "—"(后端无回测参数接口,TD-040)
 *  - BacktestPanel 取最新报告,不按 strategyId 过滤(后端 reports 无 strategyId,TD-041)
 */

const STRATEGY_TEMPLATE = `"""
策略模板 · BTC/USDT 均线交叉(KwikQuant on_bar 回调)

on_bar(bar, ctx) 每根 K 线收盘触发:
  - bar:  当前 K 线 {o, h, l, c, v, ts}
  - ctx:  交易上下文 {symbol, position, place_order, history, log}

示例:快慢均线交叉 —— 金叉做多、死叉平仓。
新建策略后可编辑,本模板仅预览、不自动保存。
"""
def on_bar(bar, ctx):
    closes = ctx.history("close", 20)
    if len(closes) < 20:
        return
    fast = sum(closes[-5:]) / 5
    slow = sum(closes[-20:]) / 20
    pos = ctx.position(ctx.symbol)
    if fast > slow and pos.qty <= 0:
        ctx.place_order(side="BUY", order_type="MARKET", amount=0.01)
        ctx.log(f"金叉做多 fast={fast:.2f} slow={slow:.2f}")
    elif fast < slow and pos.qty > 0:
        ctx.place_order(side="SELL", order_type="MARKET", amount=pos.qty)
        ctx.log(f"死叉平仓 fast={fast:.2f} slow={slow:.2f}")
`

/** 代码版本 status → 中文(meta line 显示当前编辑版本状态)。 */
const CODE_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
}

export function StrategyPage() {
  // ─── 数据 hooks ───
  const { data: strategies, isLoading: listLoading, error: listError } = useStrategies()
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const effectiveSelectedId = selectedId ?? strategies?.[0]?.id ?? null

  const { data: detail } = useStrategyDetail(effectiveSelectedId)
  const { data: codes } = useStrategyCodes(effectiveSelectedId)

  // 草稿代码(Monaco 加载 + 发布目标)
  const draftCode = useMemo(
    () => (codes ?? []).find((c) => c.status === 'DRAFT') ?? null,
    [codes],
  )
  const draftCodeId = draftCode?.id ?? null
  // activeCodeId:用户手选 tab,否则默认 draft。Editor 按 active 查 codeDetail,PUBLISHED 只读。
  const [activeCodeIdOverride, setActiveCodeIdOverride] = useState<number | null>(null)
  const activeCodeId = activeCodeIdOverride ?? draftCodeId
  const { data: codeDetail, isLoading: codeLoading } = useStrategyCodeDetail(
    effectiveSelectedId,
    activeCodeId,
  )
  // 当前 tab 是否可编辑(DRAFT 可改,PUBLISHED/ARCHIVED 只读)
  const codeReadOnly = codeDetail != null && codeDetail.status !== 'DRAFT'

  // ─── mutations ───
  const publishMut = usePublishCode()
  const readyMut = useReadyStrategy()
  const startMut = useStartStrategy()
  const pauseMut = usePauseStrategy()
  const stopMut = useStopStrategy()
  const deleteMut = useDeleteStrategy()
  const createDraftMut = useCreateCodeDraft()
  const deleteDraftMut = useDeleteCodeDraft()
  const createStrategyMut = useCreateStrategy()
  const updateDraftMut = useUpdateCodeDraft()
  // 回测提交 + 轮询
  const qc = useQueryClient()
  const submitBacktestMut = useSubmitBacktest()
  const [backtestTaskId, setBacktestTaskId] = useState<number | null>(null)
  // 回测超时兜底(M-2):WS 没推 COMPLETED/FAILED 时,5min 超时清 taskId 释放按钮
  const backtestTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  // ─── 自动保存状态 ───
  const [saveStatus, setSaveStatus] = useState<'saved' | 'saving' | 'dirty'>('saved')
  const codeRef = useRef<string>('')
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  // ─── modal 开关 ───
  const [showPublish, setShowPublish] = useState(false)
  const [showStart, setShowStart] = useState(false)
  const [showVersions, setShowVersions] = useState(false)
  const [showFSM, setShowFSM] = useState(false)
  const [showCreate, setShowCreate] = useState(false)

  // ─── 破坏性 Confirm ───
  const [pauseTarget, setPauseTarget] = useState<StrategyDetailDto | null>(null)
  const [stopTarget, setStopTarget] = useState<StrategyDetailDto | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<StrategyDetailDto | null>(null)
  const [discardTarget, setDiscardTarget] = useState<{ strategyId: number; codeId: number } | null>(null)

  // unmount 清理 save timer
  useEffect(() => {
    return () => {
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
      if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
    }
  }, [])

  // 回测 WS 推送:订阅 /topic/backtests/{userId},收到 BacktestEvent 按 taskId 匹配当前任务。
  // COMPLETED → 刷新报告列表(右侧面板自动显示)+ toast + 清 taskId;FAILED → toast + 清。
  // 替代轮询(useBacktestTask),WS 即时推送,cookie 认证(ws-contract §1)。
  const { user } = useAuth()
  const backtestTopic = user ? `/topic/backtests/${user.userId}` : null
  useWsTopic(backtestTopic, (payload) => {
    const ev = payload as { taskId: number; status: string }
    if (ev.taskId !== backtestTaskId) return // 别人的回测任务,忽略
    if (ev.status === 'COMPLETED') {
      toast.success('回测完成', { description: '结果已显示在右侧面板' })
      qc.invalidateQueries({ queryKey: backtestKeys.reports({}) })
      if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
      setBacktestTaskId(null)
    } else if (ev.status === 'FAILED') {
      toast.error('回测失败,请重试')
      if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
      setBacktestTaskId(null)
    }
  })

  // backtesting 状态 derived:提交中 或 有未完成 task(backtestTaskId 非空 = 等 WS 推完成)。
  const backtesting = submitBacktestMut.isPending || backtestTaskId != null

  const selected = detail ?? strategies?.find((s) => s.id === effectiveSelectedId) ?? null
  const latestVersion = codes && codes.length > 0 ? codes[0].versionNumber : null

  // ─── handlers ───

  function handleCodeChange(val: string | undefined) {
    codeRef.current = val ?? ''
    setSaveStatus('dirty')
    // 清旧 timer 真 debounce(防多次编辑堆积多个 timer)
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    if (effectiveSelectedId == null || draftCodeId == null) return
    const strategyId = effectiveSelectedId
    const codeId = draftCodeId
    const changelog = draftCode?.changelog ?? ''
    saveTimerRef.current = setTimeout(() => {
      setSaveStatus('saving')
      updateDraftMut.mutate(
        { strategyId, codeId, req: { sourceCode: codeRef.current, changelog } },
        {
          onSuccess: () => setSaveStatus('saved'),
          onError: () => {
            setSaveStatus('dirty')
            toast.error('自动保存失败')
          },
        },
      )
    }, 3000)
  }

  /** 切换策略/删草稿/创建策略时调:清 pending 自动保存 timer + codeRef,防旧 timer 用新代码污染旧策略草稿(B-1)。 */
  function resetAutoSave() {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    codeRef.current = ''
    setSaveStatus('saved')
  }

  function handlePause() {
    if (!pauseTarget) return
    pauseMut.mutate(pauseTarget.id, {
      onSuccess: () => {
        toast.success('策略已暂停', { description: '进程仍在运行,仅不下单' })
        setPauseTarget(null)
      },
      onError: () => toast.error('暂停失败,请重试'),
    })
  }

  function handleStop() {
    if (!stopTarget) return
    stopMut.mutate(stopTarget.id, {
      onSuccess: () => {
        toast.success('策略已停止', { description: '需重新编辑回草稿' })
        setStopTarget(null)
      },
      onError: () => toast.error('停止失败,请重试'),
    })
  }

  function handleDelete() {
    if (!deleteTarget) return
    const deletedId = deleteTarget.id
    deleteMut.mutate(deletedId, {
      onSuccess: () => {
        toast.success('策略已删除', { description: deleteTarget.name })
        setDeleteTarget(null)
        // 删的是当前选中策略 → 重置选中,自动落到列表第一个(derived)
        if (effectiveSelectedId === deletedId) {
          setSelectedId(null)
          setActiveCodeIdOverride(null)
          resetAutoSave()
        }
      },
      onError: () => toast.error('删除策略失败,请重试'),
    })
  }

  function handleStart() {
    if (!selected) return
    startMut.mutate(selected.id, {
      onSuccess: () => {
        toast.success('策略已启动', { description: 'Worker 已上线' })
        setShowStart(false)
      },
      onError: () => toast.error('启动失败,请重试'),
    })
  }

  function handlePublish(changelog: string) {
    if (!selected || draftCodeId == null) {
      toast.warning('无草稿代码可发布')
      return
    }
    const strategyId = selected.id
    const codeId = draftCodeId
    // 发布前 snapshot 刚发布代码(新草稿继承,不依赖 publish 后 codeDetail race)
    const publishedSourceCode = codeRef.current || codeDetail?.sourceCode || STRATEGY_TEMPLATE
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    updateDraftMut.mutate(
      {
        strategyId,
        codeId,
        req: {
          sourceCode: codeRef.current || codeDetail?.sourceCode || STRATEGY_TEMPLATE,
          changelog: changelog || draftCode?.changelog || '',
        },
      },
      {
        onSuccess: () => {
          publishMut.mutate(
            { strategyId, codeId },
            {
              onSuccess: () => {
                // 策略 DRAFT(首次发布)才 ready→READY;已 READY/RUNNING(新版本发布)不需 ready,
                // 否则已就绪策略 ready 失败(状态不可转)误报"标记就绪失败"
                const wasDraft = selected?.status === 'DRAFT'
                const finish = () => {
                  toast.success('版本已发布', {
                    description: wasDraft ? '策略已就绪可启动' : '新版本已上线',
                  })
                  setShowPublish(false)
                  resetAutoSave()
                  // 自动开新草稿,继承刚发布代码(用户继续迭代,不用手动 +)
                  // 后端 createDraft 409 校验:publish 后无 DRAFT,不冲突
                  createDraftMut.mutate(
                    {
                      strategyId,
                      req: { sourceCode: publishedSourceCode, changelog: '基于上一版本迭代' },
                    },
                    {
                      onSuccess: (newDraft) => setActiveCodeIdOverride(newDraft.id),
                      onError: () => toast.warning('新草稿创建失败,可手动 + 新建'),
                    },
                  )
                }
                if (wasDraft) {
                  readyMut.mutate(strategyId, {
                    onSuccess: finish,
                    onError: () =>
                      toast.warning('代码已发布,标记就绪失败,可手动启动'),
                  })
                } else {
                  finish()
                }
              },
              onError: () => toast.error('发布失败,请重试'),
            },
          )
        },
        onError: () => toast.error('更新草稿失败,请重试'),
      },
    )
  }

  function handleSubmitBacktest(range: { startTime: string; endTime: string }) {
    if (!selected || effectiveSelectedId == null) {
      toast.warning('请先选择策略')
      return
    }
    const req: SubmitBacktestRequest = {
      strategyId: effectiveSelectedId,
      symbol: selected.symbol,
      exchange: selected.exchange,
      intervalValue: selected.intervalValue,
      startTime: range.startTime,
      endTime: range.endTime,
      // 参数产品上无意义(TD-042),策略 parameters 透传或默认 {}
      parameters: selected.parameters ?? '{}',
    }
    submitBacktestMut.mutate(req, {
      onSuccess: (task) => {
        toast.info('回测已提交', { description: `任务 ${task.id} 处理中` })
        setBacktestTaskId(task.id)
        // 超时兜底:WS 没推则 5min 后清 taskId 释放按钮(M-2)
        if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
        backtestTimeoutRef.current = setTimeout(() => {
          setBacktestTaskId(null)
          toast.warning('回测超时,请重试', { description: '未收到完成推送,可能 WS 未连接' })
        }, 300_000)
      },
      onError: () => toast.error('提交回测失败'),
    })
  }

  function handleNewDraft() {
    if (effectiveSelectedId == null) {
      toast.warning('请先选择一个策略')
      return
    }
    createDraftMut.mutate(
      {
        strategyId: effectiveSelectedId,
        req: { sourceCode: STRATEGY_TEMPLATE, changelog: '新建草稿' },
      },
      {
        onSuccess: (data) => {
          toast.success('新草稿已创建')
          // 直接切到新草稿 codeId(不等 codes refetch race),useCreateCodeDraft 已 setQueryData codeDetail
          setActiveCodeIdOverride(data.id)
          resetAutoSave()
        },
        onError: (err) => {
          // 409 = 已有未发布 DRAFT(同时刻一个草稿),引导用户发布当前草稿后再创建
          if ((err as { status?: number }).status === 409) {
            toast.warning('已有未发布草稿,发布当前草稿后可创建新版本')
          } else {
            toast.error('创建草稿失败')
          }
        },
      },
    )
  }

  /**
   * 放弃草稿:破坏性操作,先 ConfirmDialog 二次确认。
   * 真删在 ConfirmDialog onConfirm(deleteDraftMut),DELETE /codes/{codeId}(仅 DRAFT 可删)。
   */
  function handleDiscardDraft(codeId: number) {
    if (effectiveSelectedId == null) return
    setDiscardTarget({ strategyId: effectiveSelectedId, codeId })
  }

  function handleDiscardConfirm() {
    if (!discardTarget) return
    const { strategyId, codeId } = discardTarget
    deleteDraftMut.mutate(
      { strategyId, codeId },
      {
        onSuccess: () => {
          toast.success('草稿已删除')
          setActiveCodeIdOverride(null)
          setDiscardTarget(null)
          resetAutoSave()
        },
        onError: () => toast.error('删除草稿失败,可能非草稿状态'),
      },
    )
  }

  function handleCreateStrategy(req: CreateStrategyRequest) {
    createStrategyMut.mutate(req, {
      onSuccess: (created) => {
        toast.success('策略已创建', { description: `${created.name} · ${created.symbol}` })
        setShowCreate(false)
        // 选中新策略
        setSelectedId(created.id)
        setActiveCodeIdOverride(null)
        resetAutoSave()
        // 自动创建初始草稿,消除"暂无代码 → 手动新建草稿"的中间态
        createDraftMut.mutate(
          {
            strategyId: created.id,
            req: { sourceCode: STRATEGY_TEMPLATE, changelog: '初始版本' },
          },
          {
            onSuccess: (data) => {
              // 直接切到初始草稿(不等 codes refetch race),否则用户需手动刷新才看到代码
              setActiveCodeIdOverride(data.id)
            },
            onError: () =>
              toast.warning('初始草稿创建失败,可手动点 + 新建'),
          },
        )
      },
      onError: () => toast.error('创建策略失败'),
    })
  }

  // ─── loading / error states ───
  if (listError) {
    return (
      <ErrorState
        title="加载失败"
        message={listError.message}
        onRetry={() => window.location.reload()}
      />
    )
  }

  if (listLoading) {
    return <LoadingState rows={8} />
  }

  if (!strategies || strategies.length === 0) {
    return (
      <div className="flex h-[calc(100vh-116px)] flex-col">
        {/* 编辑器 + 蒙层引导 */}
        <div className="relative min-h-0 flex-1">
          <Editor
            height="100%"
            defaultLanguage="python"
            theme="vs-dark"
            defaultValue={STRATEGY_TEMPLATE}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              lineNumbers: 'on',
              scrollBeyondLastLine: false,
              tabSize: 4,
              automaticLayout: true,
              readOnly: true,
            }}
          />
          {/* 蒙层 + 创建按钮 */}
          <div className="absolute inset-0 z-10 flex items-center justify-center bg-scrim/70 backdrop-blur-[2px]">
            <div className="flex flex-col items-center gap-3 rounded-2xl bg-surface-card p-8 shadow-pop">
              <div className="text-h2 font-semibold text-text-primary">开始你的第一个策略</div>
              <p className="max-w-[320px] text-center text-body-sm text-text-secondary">
                基于经典均线交叉模板,快速上手 KwikQuant 策略开发。
              </p>
              <Button size="lg" onClick={() => setShowCreate(true)}>
                <Plus className="size-4" aria-hidden />
                创建策略
              </Button>
            </div>
          </div>
        </div>
        {/* BottomControlBar disabled state */}
        <BottomControlBar
          symbol={undefined}
          interval={undefined}
          backtesting={false}
          onSubmitBacktest={() => {}}
        />
        <CreateStrategyDialog
          open={showCreate}
          onOpenChange={setShowCreate}
          creating={createStrategyMut.isPending}
          onCreate={handleCreateStrategy}
        />
      </div>
    )
  }

  return (
    <div className="flex h-[calc(100vh-116px)] flex-col">
      {/* Sub-header: 策略选择器 + 操作按钮 */}
      <StrategySelector
        strategies={strategies}
        selectedId={effectiveSelectedId}
        onSelect={(id) => {
          setSelectedId(id)
          setActiveCodeIdOverride(null) // 切换策略时重置 tab
          resetAutoSave() // 清 pending 自动保存,防旧 timer 污染新策略(B-1)
        }}
        selected={selected}
        draftCodeId={draftCodeId}
        onCreate={() => setShowCreate(true)}
        onPublish={() => setShowPublish(true)}
        onStart={() => setShowStart(true)}
        onPause={() => setPauseTarget(selected)}
        onStop={() => setStopTarget(selected)}
        onDelete={() => setDeleteTarget(selected)}
        onFsm={() => setShowFSM(true)}
      />

      {/* Main area: editor column + right panel */}
      <div className="flex min-h-0 flex-1">
        {/* Left: editor column */}
        <div className="flex min-w-0 flex-1 flex-col">
          {/* TabBar */}
          <WorkbenchTabBar
            codes={codes}
            activeCodeId={activeCodeId ?? draftCodeId}
            onTabChange={setActiveCodeIdOverride}
            onNewDraft={handleNewDraft}
            onDiscardDraft={handleDiscardDraft}
          />

          {/* Meta line */}
          <div className="flex items-center gap-sm border-b border-border-soft bg-surface-card px-base py-xxs text-caption text-text-muted">
            <span className="font-mono">Python 3.11</span>
            <span className="opacity-30">·</span>
            <Chip label={codeDetail ? (CODE_STATUS_LABEL[codeDetail.status] ?? codeDetail.status) : 'DRAFT'} size="sm" />
            {/* DRAFT 草稿可删(当前 tab 是 DRAFT 才显示);PUBLISHED/历史 tab 无删除 */}
            {activeCodeId != null && codeDetail?.status === 'DRAFT' && (
              <button
                type="button"
                onClick={() => handleDiscardDraft(activeCodeId)}
                className="flex items-center gap-xxs rounded-md px-1 text-text-muted transition-colors hover:text-down"
                title="删除草稿"
              >
                <Trash2 className="size-3" aria-hidden />
              </button>
            )}
            <div className="flex-1" />
            <button
              type="button"
              onClick={() => setShowVersions(true)}
              className="text-[11px] font-medium text-text-secondary hover:text-text-primary"
            >
              版本 ({codes?.length ?? 0})
            </button>
            <span className="opacity-30">·</span>
            <span>
              {!draftCodeId
                ? '模板预览(不自动保存)'
                : codeLoading
                  ? '加载中…'
                  : codeReadOnly
                    ? '只读·历史版本'
                    : saveStatus === 'saving'
                      ? '保存中…'
                      : saveStatus === 'dirty'
                        ? '未保存'
                        : '● 已保存'}
            </span>
          </div>

          {/* Monaco editor fills remaining space */}
          <div className="relative min-h-0 flex-1">
            {codeLoading ? (
              <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-sm bg-scrim/70 backdrop-blur-[2px]">
                <Loader2 className="size-5 animate-spin text-text-muted" aria-hidden />
                <span className="text-caption text-text-muted">加载代码…</span>
              </div>
            ) : (
              <Editor
                key={activeCodeId ?? 'template'}
                height="100%"
                defaultLanguage="python"
                theme="vs-dark"
                defaultValue={codeDetail?.sourceCode ?? STRATEGY_TEMPLATE}
                onChange={(val) => handleCodeChange(val)}
                options={{
                  minimap: { enabled: false },
                  fontSize: 13,
                  lineNumbers: 'on',
                  scrollBeyondLastLine: false,
                  tabSize: 4,
                  automaticLayout: true,
                  // PUBLISHED/ARCHIVED 历史 tab 只读,仅 DRAFT 可编辑
                  readOnly: codeReadOnly,
                  // 无 DRAFT 草稿时模板预览也只读(不自动保存)
                  ...(!draftCodeId ? { readOnly: true } : {}),
                }}
              />
            )}
            {/* 新建草稿 loading 蒙层(弱网防重复编辑,createDraftMut pending 时遮罩) */}
            {createDraftMut.isPending && (
              <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-sm bg-scrim/70 backdrop-blur-[2px]">
                <Loader2 className="size-5 animate-spin text-text-muted" aria-hidden />
                <span className="text-caption text-text-muted">正在创建草稿…</span>
              </div>
            )}
          </div>

          {/* BottomControlBar */}
          <BottomControlBar
            symbol={selected?.symbol}
            interval={selected?.intervalValue}
            backtesting={backtesting}
            onSubmitBacktest={handleSubmitBacktest}
          />
        </div>

        {/* Right: backtest panel */}
        <BacktestPanel />
      </div>

      {/* AI FAB */}
      <AiFab strategy={selected} version={latestVersion} />

      {/* ─── Dialogs ─── */}
      <PublishDialog
        open={showPublish}
        onOpenChange={setShowPublish}
        latestVersion={latestVersion}
        publishing={publishMut.isPending || readyMut.isPending}
        onPublish={handlePublish}
      />

      <StartDialog
        open={showStart}
        onOpenChange={setShowStart}
        strategy={selected}
        starting={startMut.isPending}
        onStart={handleStart}
      />

      <VersionsDialog
        open={showVersions}
        onOpenChange={setShowVersions}
        codes={codes}
        strategyName={selected?.name}
        onPublishNew={() => {
          setShowVersions(false)
          setShowPublish(true)
        }}
      />

      <FsmDialog open={showFSM} onOpenChange={setShowFSM} currentStatus={selected?.status} />

      <CreateStrategyDialog
        open={showCreate}
        onOpenChange={setShowCreate}
        creating={createStrategyMut.isPending}
        onCreate={handleCreateStrategy}
      />

      {/* ─── ConfirmDialogs ─── */}
      <ConfirmDialog
        open={pauseTarget != null}
        onOpenChange={(v) => !v && setPauseTarget(null)}
        title="确认暂停策略"
        description={`${pauseTarget?.name ?? ''}:进程仍在运行,仅不下单。可随时启动恢复。`}
        confirmLabel="暂停"
        loading={pauseMut.isPending}
        onConfirm={handlePause}
      />
      <ConfirmDialog
        open={stopTarget != null}
        onOpenChange={(v) => !v && setStopTarget(null)}
        title="确认停止策略"
        description={`${stopTarget?.name ?? ''}:终态操作,Worker 下线,需重新编辑回草稿才能再启动。`}
        confirmLabel="停止"
        destructive
        loading={stopMut.isPending}
        onConfirm={handleStop}
      />
      <ConfirmDialog
        open={deleteTarget != null}
        onOpenChange={(v) => !v && setDeleteTarget(null)}
        title="确认删除策略"
        description={`${deleteTarget?.name ?? ''}:将永久删除策略及其所有代码版本,不可恢复。`}
        confirmLabel="删除"
        destructive
        loading={deleteMut.isPending}
        onConfirm={handleDelete}
      />
      <ConfirmDialog
        open={discardTarget != null}
        onOpenChange={(v) => !v && setDiscardTarget(null)}
        title="确认删除草稿"
        description="将删除当前未发布的草稿,已发布版本不受影响。不可恢复。"
        confirmLabel="删除草稿"
        destructive
        loading={deleteDraftMut.isPending}
        onConfirm={handleDiscardConfirm}
      />
    </div>
  )
}
