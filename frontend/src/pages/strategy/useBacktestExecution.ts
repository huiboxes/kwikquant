import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { useSubmitBacktest } from '@/hooks/useBacktest'
import { backtestKeys } from '@/api/_queryKeys'
import type { SubmitBacktestRequest, BacktestTaskDto } from '@/api/backtest'
import { useWsTopic } from '@/lib/ws/useWsTopic'
import { useAuth } from '@/hooks/useAuth'
import { mapBacktestError } from './backtestError'
import type { BacktestRange } from './BottomControlBar'

/**
 * useBacktestExecution — 回测提交/进度/WS 推送处理(从 StrategyPage 拆出,Wave 3.2a)。
 *
 * 职责:提交回测(useSubmitBacktest)+ WS /topic/backtests/{userId} 推送(RUNNING 进度/
 * COMPLETED/FAILED)+ 5min 超时兜底 + "无 PUBLISHED 版本先发布后回测"预检(pendingRange
 * 由发布流程消费,见 usePublishFlow)。
 */
export function useBacktestExecution(opts: {
  strategyId: number | null
  /** 提交请求 parameters 用(策略 parameters 透传)。 */
  strategyParameters: string | null | undefined
  /** 策略代码版本列表(预检有无 PUBLISHED)。 */
  codes: { status: string }[] | undefined
  /** 回测提交成功后回调(page 层 auto-switch 右侧到回测 tab)。 */
  onSubmitted: () => void
}) {
  const { strategyId, strategyParameters, codes, onSubmitted } = opts
  const qc = useQueryClient()
  const { user } = useAuth()
  const submitBacktestMut = useSubmitBacktest()
  const [backtestTaskId, setBacktestTaskId] = useState<number | null>(null)
  // 回测进度(worker 逐 bar 上报,WS RUNNING 事件携带 processedBars/totalBars;COMPLETED/FAILED 清空)
  const [backtestProgress, setBacktestProgress] = useState<{ processed: number; total: number } | null>(null)
  // 回测超时兜底(M-2):WS 没推 COMPLETED/FAILED 时,5min 超时清 taskId 释放按钮
  const backtestTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  // 回测未发布预检(问题 1):点回测时若策略无 PUBLISHED 版本,暂存 range 弹"先发布后回测?",
  // 发布成功后 usePublishFlow 经 consumePendingBacktestRange 取回自动提交(skipPublishCheck)。
  const pendingBacktestRangeRef = useRef<BacktestRange | null>(null)
  const [showPublishPrompt, setShowPublishPrompt] = useState(false)
  // strategyId ref:WS 回调读当前策略 id(防 stale closure;useWsTopic handlerRef 持最新闭包
  // 但 strategyId 不在闭包依赖里)。刷新后 WS 守卫改"列表缓存有 taskId OR backtestTaskId 匹配",
  // 读当前策略列表缓存判断事件是否属于本策略。
  const strategyIdRef = useRef(strategyId)
  useEffect(() => {
    strategyIdRef.current = strategyId
  }, [strategyId])

  // unmount 清超时 timer(防泄露)
  useEffect(() => {
    return () => {
      if (backtestTimeoutRef.current) clearTimeout(backtestTimeoutRef.current)
    }
  }, [])

  // 回测 WS 推送:订阅 /topic/backtests/{userId},收到 BacktestEvent 按 taskId 匹配当前任务。
  // COMPLETED → 刷新报告列表(右侧面板自动显示)+ toast + 清 taskId;FAILED → toast + 清。
  // 替代轮询(useBacktestTask),WS 即时推送,cookie 认证(见 docs/ws-contract.md)。
  const backtestTopic = user ? `/topic/backtests/${user.userId}` : null
  useWsTopic(backtestTopic, (payload) => {
    // BacktestEvent schema(见 docs/ws-contract.md):{ taskId, status, processedBars?, totalBars?, error, timestamp }
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

  function handleSubmitBacktest(
    range: BacktestRange,
    submitOpts?: { skipPublishCheck?: boolean },
  ) {
    if (strategyId == null) {
      toast.warning('请先选择策略')
      return
    }
    // 预检(问题 1):策略无 PUBLISHED 版本 → 后端 POST /backtests 返 7006(NoPublishedStrategyCodeException)。
    // 与其等提交往返报错,前端预检弹"是否先发布后回测?",确认走发布 → 成功后自动回测
    // (opts.skipPublishCheck 跳过预检,代码刚 PUBLISHED)。
    if (!submitOpts?.skipPublishCheck) {
      const hasPublished = (codes ?? []).some((c) => c.status === 'PUBLISHED')
      if (!hasPublished) {
        pendingBacktestRangeRef.current = range
        setShowPublishPrompt(true)
        return
      }
    }
    const req: SubmitBacktestRequest = {
      strategyId,
      // 非阻塞改造:用 BottomControlBar 就地选的 symbol/interval(可与策略不同),
      // 不再用 selected.symbol/intervalValue —— 用户改 symbol/interval 想就地回测不同标的,
      // 不应被强制"建新策略"阻塞。与策略不同时下方另存为显式操作。
      symbol: range.symbol,
      exchange: range.exchange,
      intervalValue: range.interval,
      startTime: range.startTime,
      endTime: range.endTime,
      // 参数产品上无意义,策略 parameters 透传或默认 {}
      parameters: strategyParameters ?? '{}',
    }
    submitBacktestMut.mutate(req, {
      onSuccess: (task) => {
        // task.id 是后端回测任务表自增主键(全局递增、多用户共享),不暴露给用户。
        toast.info('回测已提交', { description: '正在用历史数据回测,完成会通知你' })
        setBacktestProgress(null)
        setBacktestTaskId(task.id)
        onSubmitted()
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

  /** 发布流程取走待回测 range(发布成功后自动回测);无 pending 返 null。 */
  function consumePendingBacktestRange(): BacktestRange | null {
    const r = pendingBacktestRangeRef.current
    pendingBacktestRangeRef.current = null
    return r
  }

  return {
    backtesting,
    backtestTaskId,
    backtestProgress,
    showPublishPrompt,
    setShowPublishPrompt,
    handleSubmitBacktest,
    consumePendingBacktestRange,
  }
}
