import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
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
  useRestartStrategy,
  usePauseStrategy,
  useStopStrategy,
  useUpdateCodeDraft,
  useCreateCodeDraft,
  useDeleteCodeDraft,
  useCreateStrategy,
  useDeleteStrategy,
} from '@/hooks/useStrategies'
import { useAccounts } from '@/hooks/useAccounts'
import { useUiStore, type Exchange } from '@/stores/uiStore'
import type { StrategyDetailDto, CreateStrategyRequest } from '@/api/strategy'

// 子组件
import { StrategySelector } from './strategy/StrategySelector'
import { WorkbenchTabBar } from './strategy/WorkbenchTabBar'
import { BottomControlBar } from './strategy/BottomControlBar'
import { RightPanel, type RightTab } from './strategy/RightPanel'
import { PublishDialog } from './strategy/PublishDialog'
import { StartDialog } from './strategy/StartDialog'
import { VersionsDialog } from './strategy/VersionsDialog'
import { CreateStrategyDialog } from './strategy/CreateStrategyDialog'
import { FsmDialog } from './strategy/FsmDialog'
import { PRESET_STRATEGIES } from './strategy/presetStrategies'
import { mapBacktestError } from './strategy/backtestError'
import { useSubmitBacktest } from '@/hooks/useBacktest'
import { backtestKeys } from '@/api/_queryKeys'
import { useQueryClient } from '@tanstack/react-query'
import type { SubmitBacktestRequest, BacktestTaskDto } from '@/api/backtest'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { useAuth } from '@/hooks/useAuth'

/**
 * StrategyPage — 策略工作台(IDE 布局,照原型 workbench.html)。
 *
 * 布局:Sub-header(策略选择器+操作按钮) + flex row(编辑器列+右侧回测面板) + AI FAB。
 * 编辑器列:TabBar → Meta line → Monaco(flex-1) → BottomControlBar。
 *
 * honest 差异:
 *  - 后端无策略 update 端点:改 symbol/interval 就地覆盖回测参数(非阻塞),与策略不同时显式「另存为新策略」fork 新策略
 *  - 日期范围已接:handleSubmitBacktest 用 BottomControlBar 选的 startTime/endTime/symbol/interval/exchange(非占位)
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
  // 从 URL ?symbol=&marketType= 预填新建策略(行情页"策"按钮 + 交易页"写策略"跳转用)
  const [searchParams] = useSearchParams()
  const querySymbol = searchParams.get('symbol') ?? undefined
  const queryMarketType = (searchParams.get('marketType') as 'SPOT' | 'PERP' | null) ?? undefined

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
  const restartMut = useRestartStrategy()
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
  // 回测进度(worker 逐 bar 上报,WS RUNNING 事件携带 processedBars/totalBars;COMPLETED/FAILED 清空)
  const [backtestProgress, setBacktestProgress] = useState<{ processed: number; total: number } | null>(null)
  // 回测交易所(从 uiStore 取,项目基准 OKX,对齐后端 application.yaml + AuthService;
  // CreateStrategyDialog/AddAccountDialog 共享此单一来源,避免默认值分裂)。
  // 原 useState('OKX') + useEffect 账户回灌已删 — useEffect guard 逻辑反了(切到 OKX
  // 被账户数据回灌成 BINANCE,正是"切换不起作用"根因);store 是单一来源,无需回灌。
  const exchange = useUiStore((s) => s.exchange)
  const setExchange = useUiStore((s) => s.setExchange)
  const handleExchangeChange = (v: string) => setExchange(v as Exchange)
  const { data: accounts } = useAccounts()
  // 回测超时兜底(M-2):WS 没推 COMPLETED/FAILED 时,5min 超时清 taskId 释放按钮
  const backtestTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  // strategyId ref:WS 回调读当前策略 id(防 stale closure;useWsTopic handlerRef 持最新闭包
  // 但 strategyId 不在闭包依赖里)。刷新后 WS 守卫改"列表缓存有 taskId OR backtestTaskId 匹配",
  // 读当前策略列表缓存判断事件是否属于本策略。
  const strategyIdRef = useRef(effectiveSelectedId)
  useEffect(() => {
    strategyIdRef.current = effectiveSelectedId
  }, [effectiveSelectedId])

  // ─── 回测 symbol/interval(非阻塞:与策略可不同,就地覆盖回测参数)───
  // 改造(2026-07-24):不再一改 symbol/interval 就弹"创建新策略"阻塞式 fork,
  // 而是就地覆盖回测参数,与策略不同时 BottomControlBar 显示非阻塞"另存为新策略"按钮。
  const [backtestSymbol, setBacktestSymbol] = useState<string | undefined>(undefined)
  const [backtestInterval, setBacktestInterval] = useState<string | undefined>(undefined)
  // lastSyncedId guard:只在切策略 + 该策略 detail 加载后同步一次,避免
  //   (a) detail 未就绪时同步成 undefined→控制栏显示 BTC/USDT 而非策略 symbol,
  //   (b) detail refetch(如 invalidate)时重置用户已改的 override。
  // react-query 按 effectiveSelectedId 取 detail,detail 非空即当前策略的。
  const lastSyncedIdRef = useRef<number | null>(null)
  useEffect(() => {
    if (
      effectiveSelectedId != null &&
      detail != null &&
      lastSyncedIdRef.current !== effectiveSelectedId
    ) {
      setBacktestSymbol(detail.symbol)
      setBacktestInterval(detail.intervalValue)
      lastSyncedIdRef.current = effectiveSelectedId
    }
  }, [effectiveSelectedId, detail])

  // ─── 自动保存状态 ───
  const [saveStatus, setSaveStatus] = useState<'saved' | 'saving' | 'dirty'>('saved')
  const codeRef = useRef<string>('')
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  // ─── modal 开关 ───
  const [showPublish, setShowPublish] = useState(false)
  const [showStart, setShowStart] = useState(false)
  const [showVersions, setShowVersions] = useState(false)
  const [showFSM, setShowFSM] = useState(false)
  // ?symbol= 存在(行情页"策"按钮/交易页"写策略"跳转带)→ 初始 open "创建新策略" dialog(预填 symbol)
  const [showCreate, setShowCreate] = useState(!!querySymbol)
  // 右侧 tab(会话默认,回测提交时 auto-switch 到回测 tab 显进度;WS 完成后 running 清 false 自动显结果)
  const [rightTab, setRightTab] = useState<RightTab>('session')
  // Bug3:会话窗口全屏(占用代码空间)。全屏时编辑器列隐藏,RightPanel 铺满主区。
  // 切 tab 自动退出全屏(BacktestPanel 无全屏按钮,避免卡全屏态)。
  const [sessionFullscreen, setSessionFullscreen] = useState(false)

  // ─── 破坏性 Confirm ───
  const [pauseTarget, setPauseTarget] = useState<StrategyDetailDto | null>(null)
  const [stopTarget, setStopTarget] = useState<StrategyDetailDto | null>(null)
  // 非阻塞改造:改 symbol/interval 不再弹阻塞式 fork。BottomControlBar 就地覆盖回测参数,
  // 用户点"另存为新策略"显式按钮才弹此 Confirm(不挡回测)。后端无 update 端点,只能 fork 新策略。
  const [saveAsTarget, setSaveAsTarget] = useState<{ symbol: string; interval: string; exchange: Exchange } | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<StrategyDetailDto | null>(null)
  const [discardTarget, setDiscardTarget] = useState<{ strategyId: number; codeId: number } | null>(null)
  // 回测未发布预检(问题 1):点回测时若策略无 PUBLISHED 版本,后端 POST /backtests 返
  // 7006(NoPublishedStrategyCodeException)。与其等提交往返报错,前端预检弹"是否先发布后
  // 回测?",确认 → handlePublish('') → publishMut.onSuccess 自动调 handleSubmitBacktest
  // (pending, {skipPublishCheck}) 跳过预检(代码刚 PUBLISHED),丝滑完成"发布+回测"。
  const pendingBacktestRangeRef = useRef<{
    startTime: string
    endTime: string
    exchange: string
    symbol: string
    interval: string
  } | null>(null)
  const [showPublishPrompt, setShowPublishPrompt] = useState(false)

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
    // BacktestEvent schema(ws-contract §3.6):{ taskId, status, processedBars?, totalBars?, error, timestamp }
    // error 仅 FAILED 有值 —— 透出后端失败原因,否则用户只看到笼统"请重试"无从诊断。
    const ev = payload as {
      taskId: number
      status: string
      processedBars?: number
      totalBars?: number
      error?: string | null
    }
    // 守卫:事件属于当前策略的 task(列表缓存有)OR 本 tab 刚 submit 的 backtestTaskId 匹配。
    // 刷新后 backtestTaskId=null(纯内存态丢),但列表轮询 5s 内 refetch 到 RUNNING task,
    // WS 事件即匹配列表 → 进处理;不在当前策略列表且非本 tab 发起的忽略(别的策略/别的 tab)。
    const sid = strategyIdRef.current
    const tasks =
      sid != null
        ? (qc.getQueryData<BacktestTaskDto[]>(backtestKeys.tasks(sid)) ?? [])
        : []
    if (!tasks.some((t) => t.id === ev.taskId) && ev.taskId !== backtestTaskId) return
    if (ev.status === 'RUNNING') {
      // worker 逐 bar 上报(节流 ~200 bar/次),更新进度条;不清 taskId(仍 running)
      setBacktestProgress({
        processed: ev.processedBars ?? 0,
        total: ev.totalBars ?? 0,
      })
      // 收到进度 = 回测存活,续命 idle 超时(防 klines 慢拉取 + 大量 bar 累积超 5min 误判超时,
      // 否则 worker 仍在跑却被判超时清 taskId,后续真实 COMPLETED 被 taskId mismatch 丢弃)
      if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
      backtestTimeoutRef.current = setTimeout(() => {
        setBacktestProgress(null)
        setBacktestTaskId(null)
        toast.warning('回测超时,请重试', { description: '未收到完成通知,请检查网络后重试' })
      }, 300_000)
      return
    }
    if (ev.status === 'COMPLETED') {
      toast.success('回测完成', { description: '结果已显示在右侧面板' })
      // invalidate all(含 tasks/reports/reportDetail/task):BacktestPanel 走
      // useBacktestTasksByStrategy → 最新 COMPLETED task.reportId → useReportDetail,
      // 只 invalidate reports(旧 useReports key)会让 tasks 不 refetch → 新 COMPLETED task
      // 不进列表 → latestCompleted undefined → "暂无回测结果"(回归,需手动刷新才出)。
      qc.invalidateQueries({ queryKey: backtestKeys.all })
      if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
      setBacktestProgress(null)
      setBacktestTaskId(null)
    } else if (ev.status === 'FAILED') {
      // 后端 error 是英文断言文案(如 'trades must not be empty'),映射成产品化文案 +
      // 可行动建议。"无成交"用 warning(非错误),真实异常用 error 透原因。
      const f = mapBacktestError(ev.error)
      if (f.tone === 'warning') {
        toast.warning(f.title, { description: f.description })
      } else {
        toast.error(f.title, { description: f.description })
      }
      if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
      setBacktestProgress(null)
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
        toast.success('策略已暂停', { description: '策略仍保持运行,仅暂停下单' })
        setPauseTarget(null)
      },
      onError: () => toast.error('暂停失败,请重试'),
    })
  }

  function handleStop() {
    if (!stopTarget) return
    stopMut.mutate(stopTarget.id, {
      onSuccess: () => {
        toast.success('策略已停止', { description: '可随时重新启动' })
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

  function handleStart(accountId: number) {
    if (!selected) return
    // STOPPED → restart(POST /restart);READY → start(POST /start)。StartDialog 按 status 分流。
    if (selected.status === 'STOPPED') {
      restartMut.mutate({ id: selected.id, accountId }, {
        onSuccess: () => {
          toast.success('策略已重新启动', { description: '策略已恢复接收行情并执行下单' })
          setShowStart(false)
        },
        onError: () => toast.error('重新启动失败,请重试'),
      })
    } else {
      startMut.mutate({ id: selected.id, accountId }, {
        onSuccess: () => {
          toast.success('策略已启动', { description: '策略已开始接收行情并执行下单' })
          setShowStart(false)
        },
        onError: () => toast.error('启动失败,请重试'),
      })
    }
  }

  function handlePublish(changelog: string) {
    if (!selected || draftCodeId == null) {
      toast.warning('没有可发布的草稿代码')
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
                // 问题 1 自动回测:用户从回测按钮触发发布(pendingBacktestRangeRef 非空)
                // → 发布成功后自动回测(skipPublishCheck 跳过预检,代码刚 PUBLISHED)。
                if (pendingBacktestRangeRef.current) {
                  const pendingRange = pendingBacktestRangeRef.current
                  pendingBacktestRangeRef.current = null
                  handleSubmitBacktest(pendingRange, { skipPublishCheck: true })
                }
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
                      onError: () => toast.warning('新草稿创建失败,可手动新建'),
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

  function handleSubmitBacktest(range: {
    startTime: string
    endTime: string
    exchange: string
    symbol: string
    interval: string
  }, opts?: { skipPublishCheck?: boolean }) {
    if (!selected || effectiveSelectedId == null) {
      toast.warning('请先选择策略')
      return
    }
    // 预检(问题 1):策略无 PUBLISHED 版本 → 后端 POST /backtests 返 7006(NoPublishedStrategyCodeException)。
    // 与其等提交往返报错,前端预检弹"是否先发布后回测?",确认走发布 → 成功后自动回测
    // (opts.skipPublishCheck 跳过预检,代码刚 PUBLISHED)。
    if (!opts?.skipPublishCheck) {
      const hasPublished = (codes ?? []).some((c) => c.status === 'PUBLISHED')
      if (!hasPublished) {
        pendingBacktestRangeRef.current = range
        setShowPublishPrompt(true)
        return
      }
    }
    const req: SubmitBacktestRequest = {
      strategyId: effectiveSelectedId,
      // 非阻塞改造:用 BottomControlBar 就地选的 symbol/interval(可与策略不同),
      // 不再用 selected.symbol/intervalValue —— 用户改 symbol/interval 想就地回测不同标的,
      // 不应被强制"建新策略"阻塞。与策略不同时下方另存为显式操作。
      symbol: range.symbol,
      exchange: range.exchange,
      intervalValue: range.interval,
      startTime: range.startTime,
      endTime: range.endTime,
      // 参数产品上无意义(TD-042),策略 parameters 透传或默认 {}
      parameters: selected.parameters ?? '{}',
    }
    submitBacktestMut.mutate(req, {
      onSuccess: (task) => {
        // task.id 是后端回测任务表自增主键(全局递增、多用户共享),不暴露给用户。
        toast.info('回测已提交', { description: '正在用历史数据回测,完成会通知你' })
        setBacktestProgress(null)
        setBacktestTaskId(task.id)
        // auto-switch 右侧到回测 tab 显进度(用户要求:开始回测后右侧多回测 tab 显结果/进度)
        setRightTab('backtest')
        // 超时兜底:WS 没推则 5min 后清 taskId 释放按钮(M-2)
        if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
        backtestTimeoutRef.current = setTimeout(() => {
          setBacktestProgress(null)
          setBacktestTaskId(null)
          toast.warning('回测超时,请重试', { description: '未收到完成通知,请检查网络后重试' })
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

  function handleCreateStrategy(
    req: CreateStrategyRequest,
    opts?: { presetKey?: string; sourceCode?: string },
  ) {
    // source 优先级:显式 override(fork 继承当前代码)> 预置模版 > 默认均线交叉模版
    const preset = opts?.presetKey
      ? PRESET_STRATEGIES.find((p) => p.key === opts.presetKey)
      : undefined
    const initialSource = opts?.sourceCode ?? preset?.sourceCode ?? STRATEGY_TEMPLATE
    const changelog = preset
      ? `预置模版:${preset.name}`
      : opts?.sourceCode
        ? '基于源策略 fork'
        : '初始版本'
    createStrategyMut.mutate(req, {
      onSuccess: (created) => {
        toast.success('策略已创建', { description: `${created.name} · ${created.symbol}` })
        setShowCreate(false)
        // 选中新策略
        setSelectedId(created.id)
        setActiveCodeIdOverride(null)
        resetAutoSave()
        // 自动创建初始草稿(预置模版/fork 继承/默认模版),消除"暂无代码 → 手动新建草稿"的中间态
        createDraftMut.mutate(
          {
            strategyId: created.id,
            req: { sourceCode: initialSource, changelog },
          },
          {
            onSuccess: (data) => {
              // 直接切到初始草稿(不等 codes refetch race),否则用户需手动刷新才看到代码
              setActiveCodeIdOverride(data.id)
            },
            onError: () =>
              toast.warning('初始草稿创建失败,可手动新建'),
          },
        )
      },
      onError: () => toast.error('创建策略失败'),
    })
  }

  // 非阻塞改造:用户点"另存为新策略"显式按钮 → 弹 Confirm(不挡回测,backtest 仍用就地选的 symbol/interval)。
  // 后端无 update 端点,只能 fork 新策略(原策略不变)。
  function handleSaveAsNewStrategy() {
    const sym = backtestSymbol ?? selected?.symbol
    const itv = backtestInterval ?? selected?.intervalValue
    if (!sym || !itv) return
    // exchange/symbol/interval 任一与策略不同 → 提示另存。exchange 从 uiStore 取
    // (BottomControlBar 的 exchange 已是 store 值);策略 exchange 来自 selected.exchange
    if (
      sym === selected?.symbol &&
      itv === selected?.intervalValue &&
      exchange === selected?.exchange
    ) {
      toast.info('当前参数与策略一致,无需另存')
      return
    }
    setSaveAsTarget({ symbol: sym, interval: itv, exchange })
  }

  function handleSaveAsConfirm() {
    if (!saveAsTarget || !selected) return
    const req: CreateStrategyRequest = {
      name: `${selected.name}-fork`,
      description: selected.description,
      symbol: saveAsTarget.symbol,
      // fork 用 saveAsTarget.exchange(BottomControlBar 选的),而非源策略 exchange ——
      // 让"改 exchange 走 fork"真正落地:fork 出新策略用新交易所,原策略不变(TD-039)
      exchange: saveAsTarget.exchange,
      marketType: selected.marketType,
      intervalValue: saveAsTarget.interval,
      parameters: '{}',
    }
    setSaveAsTarget(null)
    // fork 继承源策略当前代码(codeRef/草稿 source),非默认模版
    const forkSource = codeRef.current || codeDetail?.sourceCode || STRATEGY_TEMPLATE
    handleCreateStrategy(req, { sourceCode: forkSource })
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
          symbol="BTC/USDT"
          interval="1h"
          strategySymbol={undefined}
          strategyInterval={undefined}
          strategyExchange={undefined}
          exchange={exchange}
          backtesting={false}
          onSubmitBacktest={() => {}}
          onExchangeChange={handleExchangeChange}
        />
        <CreateStrategyDialog
          open={showCreate}
          onOpenChange={setShowCreate}
          creating={createStrategyMut.isPending}
          onCreate={handleCreateStrategy}
          symbol={querySymbol}
          marketType={queryMarketType}
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
        onStart={() => {
          if (!selected) return
          if (selected.status === 'PAUSED' || selected.status === 'ERROR') {
            // resume(PAUSED→RUNNING)/重试(ERROR→RUNNING):用已绑账户,不弹 StartDialog(最小惊讶)
            startMut.mutate({ id: selected.id }, {
              onSuccess: () => toast.success('策略已启动', { description: '策略已开始接收行情并执行下单' }),
              onError: () => toast.error('启动失败,请重试'),
            })
          } else {
            // READY 首次 start / STOPPED 重新启动:StartDialog 选账户(handleStart 按 status 分流 start/restart)
            setShowStart(true)
          }
        }}
        onPause={() => setPauseTarget(selected)}
        onStop={() => setStopTarget(selected)}
        onDelete={() => setDeleteTarget(selected)}
        onFsm={() => setShowFSM(true)}
      />

      {/* Main area: editor column + right panel */}
      <div className="flex min-h-0 flex-1">
        {/* Left: editor column(会话全屏时隐藏,让出空间给 RightPanel) */}
        <div className={`${sessionFullscreen ? 'hidden' : 'flex'} min-w-0 flex-1 flex-col`}>
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
            <Chip label={codeDetail ? (CODE_STATUS_LABEL[codeDetail.status] ?? '未知') : '草稿'} size="sm" />
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
                        : '已保存'}
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
                onMount={(editor) => {
                  // codeRef 接编辑器内容:Monaco defaultValue 不触发 onChange,codeRef 会一直停在
                  // 初始 ''(AI 会话 editorCodeRef 读到空 → sourceCode="" → 后端 EDITOR+空判 400/401)。
                  // onMount 在 key=activeCodeId remount 后触发,editor.getValue() 即当前显示代码。
                  codeRef.current = editor.getValue() ?? ''
                }}
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
            symbol={backtestSymbol ?? selected?.symbol ?? 'BTC/USDT'}
            interval={backtestInterval ?? selected?.intervalValue ?? '1h'}
            strategySymbol={selected?.symbol}
            strategyInterval={selected?.intervalValue}
            strategyExchange={selected?.exchange}
            exchange={exchange}
            marketType={selected?.marketType}
            backtesting={backtesting}
            onSubmitBacktest={handleSubmitBacktest}
            onSymbolChange={setBacktestSymbol}
            onIntervalChange={setBacktestInterval}
            onExchangeChange={handleExchangeChange}
            onSaveAsNewStrategy={handleSaveAsNewStrategy}
          />
        </div>

        {/* Right: tabbed panel(会话默认 + 回测 tab,回测提交 auto-switch 显进度/结果) */}
        <RightPanel
          strategy={selected}
          version={latestVersion}
          editorCodeRef={codeRef}
          activeTab={rightTab}
          onTabChange={(tab) => {
            setRightTab(tab)
            setSessionFullscreen(false) // 切 tab 退出全屏(避免 backtest 卡全屏无退出按钮)
          }}
          running={backtestTaskId != null}
          progress={backtestProgress}
          fullscreen={sessionFullscreen}
          onToggleFullscreen={() => setSessionFullscreen((v) => !v)}
        />
      </div>

      {/* ─── Dialogs ─── */}
      <PublishDialog
        open={showPublish}
        onOpenChange={setShowPublish}
        latestVersion={latestVersion}
        publishing={publishMut.isPending || readyMut.isPending}
        onPublish={handlePublish}
      />

      {/* 回测未发布预检(问题 1):点回测时策略无 PUBLISHED 版本 → 弹此 → 确认走发布 →
          publishMut.onSuccess 自动回测(pendingBacktestRangeRef)丝滑完成"发布+回测"。 */}
      <ConfirmDialog
        open={showPublishPrompt}
        onOpenChange={setShowPublishPrompt}
        title="未发布版本,是否先发布后回测?"
        description="回测需基于已发布的代码版本运行。确认后将自动发布当前草稿并开始回测。"
        confirmLabel={publishMut.isPending || updateDraftMut.isPending ? '发布中…' : '发布并回测'}
        loading={publishMut.isPending || updateDraftMut.isPending}
        onConfirm={() => {
          setShowPublishPrompt(false)
          handlePublish('')
        }}
      />

      <StartDialog
        open={showStart}
        onOpenChange={setShowStart}
        strategy={selected}
        accounts={(accounts ?? []).filter((a) => a.exchange === selected?.exchange)}
        starting={startMut.isPending || restartMut.isPending}
        onStart={handleStart}
        hasUnpublishedDraft={draftCodeId != null}
        onEditCode={() => {
          // 第一版:dialog 由 StartDialog 内部调 onOpenChange(false) 关闭,编辑器 tab 在页面中间自然可见
          // (StrategyPage 无独立 tab state,activeCodeIdOverride 是选 code 版本非切 tab,故不切)
        }}
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
        symbol={querySymbol}
        marketType={queryMarketType}
      />

      {/* ─── ConfirmDialogs ─── */}
      <ConfirmDialog
        open={pauseTarget != null}
        onOpenChange={(v) => !v && setPauseTarget(null)}
        title="确认暂停策略"
        description={`${pauseTarget?.name ?? ''}:策略仍保持运行,仅暂停下单,可随时恢复。`}
        confirmLabel="暂停"
        loading={pauseMut.isPending}
        onConfirm={handlePause}
      />
      <ConfirmDialog
        open={stopTarget != null}
        onOpenChange={(v) => !v && setStopTarget(null)}
        title="确认停止策略"
        description={`${stopTarget?.name ?? ''}:停止后策略退出运行,可随时重新启动。`}
        confirmLabel="停止"
        destructive
        loading={stopMut.isPending}
        onConfirm={handleStop}
      />
      <ConfirmDialog
        open={saveAsTarget != null}
        onOpenChange={(v) => !v && setSaveAsTarget(null)}
        title="另存为新策略?"
        description={
          saveAsTarget
            ? `将以 ${saveAsTarget.symbol} · ${saveAsTarget.interval} 基于「${selected?.name ?? ''}」创建新策略,原策略与当前回测不受影响。`
            : ''
        }
        confirmLabel={createStrategyMut.isPending ? '创建中…' : '另存为新策略'}
        loading={createStrategyMut.isPending}
        onConfirm={handleSaveAsConfirm}
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
