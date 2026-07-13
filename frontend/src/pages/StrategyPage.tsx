import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  AlertTriangle,
  FileCode,
  GitBranch,
  History,
  Play,
  Plus,
  Send,
  Square,
} from 'lucide-react'
import { toast } from 'sonner'
import Editor from '@monaco-editor/react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/Chip'
import { StrategyStatusBadge } from '@/components/StrategyStatusBadge'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { EmptyState } from '@/components/EmptyState'
import { ErrorState } from '@/components/ErrorState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
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
} from '@/hooks/useStrategies'
import { useLlmKeys } from '@/hooks/useSettings'
import { useStreamChat } from '@/hooks/useStreamChat'
import { formatDateTime } from '@/lib/format'
import type { StrategyDetailDto, StrategyCodeDto } from '@/api/strategy'

/**
 * StrategyPage — 策略工作台(照 prototypes/done-design/components/StrategyPage.jsx port)。
 * 4 块:Header(name+status+chips+buttons) / 策略 list rail / 主 grid(Monaco 代码编辑器 + AIChat SSE 流式)
 * + 4 modal(发布/启动/版本/FSM)+ 暂停/停止 ConfirmDialog。
 *
 * honest 差异(不静默照做,记 TD-032~038):
 *  - StrategyDetailDto 无 version/pnl/lines:version 从 codes list[0].versionNumber 派生;
 *    lines 从 codeDetail.sourceCode.split('\n').length 派生;pnl 无端点占位 "—"(TD-036)
 *  - start 契约描述只说 READY→RUNNING,前端也用于 PAUSED→RUNNING resume(TD-033)
 *  - 新建策略:占位 toast(无新建 modal,TD-037)
 *  - 启动 modal 绑定账户 select 是 UX only(后端 start 不接 account,TD-038)
 *  - READY/ERROR 状态:StrategyStatusBadge 只 4 态,显 neutral 文本(TD-034)
 *  - AI chat llmKeyId:取首个 LLM key;无 key 提示配置(TD-035)
 */

const SUGGESTIONS = [
  '加一个 ADX 过滤震荡市',
  '改成 swing low 止损',
  '帮我加上资金费率过滤',
  '把 stop_loss 改成 trailing',
]

// 3 代码文件 tab(原型 onBar.py / config.json / requirements.txt;只 onBar.py 有编辑器,其余占位)
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

const CODE_TABS = ['onBar.py', 'config.json', 'requirements.txt'] as const

/** 把 AI 文本按 ``` 代码块分段渲染。 */
function renderChatContent(text: string) {
  const parts = text.split('```')
  return parts.map((seg, idx) => {
    if (idx % 2 === 1) {
      return (
        <pre
          key={idx}
          className="my-1.5 overflow-auto rounded-md bg-surface-card-2 p-2.5 font-mono text-[11px] text-text-primary"
        >
          {seg}
        </pre>
      )
    }
    return (
      <span key={idx} className="whitespace-pre-wrap break-words">
        {seg}
      </span>
    )
  })
}

// ─── AIChat ───
function AIChat({
  strategy,
  version,
}: {
  strategy: StrategyDetailDto
  version: number | null
}) {
  const { data: llmKeys } = useLlmKeys()
  const llmKeyId = llmKeys && llmKeys.length > 0 ? llmKeys[0].id : null
  const { messages, streaming, streamText, draft, setDraft, send } = useStreamChat()
  const endRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages, streamText, streaming])

  const handleSend = () => {
    send(draft, llmKeyId, strategy.id)
  }

  return (
    <div className="flex h-[460px] flex-col overflow-hidden rounded-lg border border-border-soft bg-surface-card">
      {/* header */}
      <div className="flex items-center justify-between border-b border-border-soft px-3.5 py-2.5">
        <div className="flex items-center gap-2">
          <div className="flex size-6 items-center justify-center rounded-md bg-accent text-caption font-bold text-on-accent">
            AI
          </div>
          <div>
            <div className="text-body-sm font-semibold text-text-primary">策略编码助手</div>
            <div className="text-[10px] text-text-muted">
              已注入上下文 · {strategy.name} · {version ? `v${version}` : '未发布'}
            </div>
          </div>
        </div>
        <Chip color="accent" label="SSE 流式" />
      </div>

      {/* msgs */}
      <div className="flex flex-1 flex-col gap-3.5 overflow-auto px-3.5 py-3">
        {messages.map((m, i) => {
          const isUser = m.role === 'user'
          return (
            <div
              key={i}
              className={`flex gap-2 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}
            >
              <div
                className={`flex size-6 shrink-0 items-center justify-center text-[11px] font-bold ${
                  isUser
                    ? 'bg-surface-3 text-text-primary'
                    : 'bg-accent text-on-accent'
                }`}
                style={{ borderRadius: 6 }}
              >
                {isUser ? '你' : 'AI'}
              </div>
              <div className="max-w-[82%]">
                <div
                  className={`mb-0.5 text-[10px] text-text-muted ${
                    isUser ? 'text-right' : 'text-left'
                  }`}
                >
                  {m.ts}
                </div>
                <div
                  className={`rounded-lg border border-border-soft px-3 py-2 text-caption leading-relaxed text-text-primary ${
                    isUser ? 'bg-surface-card-2' : 'bg-accent-soft'
                  }`}
                  style={{
                    borderTopRightRadius: isUser ? 2 : 10,
                    borderTopLeftRadius: isUser ? 10 : 2,
                  }}
                >
                  {renderChatContent(m.content)}
                </div>
              </div>
            </div>
          )
        })}
        {streaming && (
          <div className="flex gap-2">
            <div
              className="flex size-6 shrink-0 items-center justify-center bg-accent text-[11px] font-bold text-on-accent"
              style={{ borderRadius: 6 }}
            >
              AI
            </div>
            <div className="flex-1">
              <div className="mb-0.5 text-[10px] text-text-muted">正在生成…</div>
              <div className="kq-stream-cursor whitespace-pre-wrap text-caption leading-relaxed text-text-primary">
                {streamText}
              </div>
            </div>
          </div>
        )}
        <div ref={endRef} />
      </div>

      {/* suggestions */}
      {!streaming && (
        <div className="flex flex-wrap gap-1.5 px-3.5">
          {SUGGESTIONS.map((s) => (
            <button
              key={s}
              onClick={() => setDraft(s)}
              className="rounded-full border border-border-soft bg-surface-card-2 px-2.5 py-1 text-[11px] text-text-secondary transition hover:bg-surface-3"
            >
              {s}
            </button>
          ))}
        </div>
      )}

      {/* draft input */}
      <div className="flex items-end gap-2 border-t border-border-soft px-3.5 py-2.5">
        <Textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          placeholder="问 AI 关于当前策略的问题…(Enter 发送,Shift+Enter 换行)"
          className="min-h-[40px] max-h-[120px] flex-1 resize-none bg-surface-card-2 text-caption"
        />
        <Button onClick={handleSend} disabled={streaming || !draft.trim()} size="sm">
          <Send className="size-3.5" aria-hidden />
          发送
        </Button>
      </div>
    </div>
  )
}

// ─── 主页 ───
export function StrategyPage() {
  const navigate = useNavigate()

  // 策略列表 + selected
  const { data: strategies, isLoading: listLoading, error: listError } = useStrategies()
  const [selectedId, setSelectedId] = useState<number | null>(null)

  // 首次加载默认选第一个(derived,不靠 effect 设 state 避免 set-state-in-effect)
  const effectiveSelectedId = selectedId ?? strategies?.[0]?.id ?? null

  const { data: detail } = useStrategyDetail(effectiveSelectedId)
  const { data: codes } = useStrategyCodes(effectiveSelectedId)

  // 草稿代码(Monaco 加载 + 发布目标)
  const draftCode = useMemo(
    () => (codes ?? []).find((c) => c.status === 'DRAFT') ?? null,
    [codes],
  )
  const draftCodeId = draftCode?.id ?? null
  const { data: codeDetail, isLoading: codeLoading } = useStrategyCodeDetail(
    effectiveSelectedId,
    draftCodeId,
  )

  // mutations
  const publishMut = usePublishCode()
  const readyMut = useReadyStrategy()
  const startMut = useStartStrategy()
  const pauseMut = usePauseStrategy()
  const stopMut = useStopStrategy()
  // 自动保存(updateDraft)— Monaco onChange debounce 3s
  const [saveStatus, setSaveStatus] = useState<'saved' | 'saving' | 'dirty'>('saved')
  const codeRef = useRef<string>('')
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const updateDraftMut = useUpdateCodeDraft()

  // modal 开关
  const [showPublish, setShowPublish] = useState(false)
  const [showStart, setShowStart] = useState(false)
  const [showVersions, setShowVersions] = useState(false)
  const [showFSM, setShowFSM] = useState(false)
  const [activeTab, setActiveTab] = useState<(typeof CODE_TABS)[number]>('onBar.py')

  // 破坏性 Confirm
  const [pauseTarget, setPauseTarget] = useState<StrategyDetailDto | null>(null)
  const [stopTarget, setStopTarget] = useState<StrategyDetailDto | null>(null)

  // 发布表单
  const [publishVersion, setPublishVersion] = useState('')
  const [publishChangelog, setPublishChangelog] = useState('')

  // 启动账户选择
  const [startAccount, setStartAccount] = useState('PAPER')

  // unmount 清理 save timer(纯 cleanup,不 setState)
  useEffect(() => {
    return () => {
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    }
  }, [])

  const selected = detail ?? strategies?.find((s) => s.id === effectiveSelectedId) ?? null
  const latestVersion = codes && codes.length > 0 ? codes[0].versionNumber : null
  const codeLines = codeDetail ? codeDetail.sourceCode.split('\n').length : null

  // ─── handlers ───

  function handleCodeChange(val: string | undefined) {
    codeRef.current = val ?? ''
    setSaveStatus('dirty')
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    if (effectiveSelectedId == null || draftCodeId == null) return
    const strategyId = effectiveSelectedId
    const codeId = draftCodeId
    const changelog = draftCode?.changelog ?? ''
    saveTimerRef.current = setTimeout(() => {
      setSaveStatus('saving')
      updateDraftMut.mutate(
        {
          strategyId,
          codeId,
          req: { sourceCode: codeRef.current, changelog },
        },
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

  function handlePublish() {
    if (!selected || draftCodeId == null) {
      toast.warning('无草稿代码可发布')
      return
    }
    const strategyId = selected.id
    const codeId = draftCodeId
    // 清自动保存定时器,避编辑后 3s 内点发布触发自动保存 PUT 撞发布后 409(N-1)
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    // 先 PUT 更新草稿 changelog(发布时冻结到该版本),再 publish(DRAFT→PUBLISHED),再 ready(DRAFT→READY)
    // publishVersion 是装饰(后端按 versionNumber 递增派生,不传);changelog 经 PUT 写入草稿
    updateDraftMut.mutate(
      {
        strategyId,
        codeId,
        req: {
          sourceCode: codeRef.current || codeDetail?.sourceCode || '',
          changelog: publishChangelog || draftCode?.changelog || '',
        },
      },
      {
        onSuccess: () => {
          publishMut.mutate(
            { strategyId, codeId },
            {
              onSuccess: () => {
                readyMut.mutate(strategyId, {
                  onSuccess: () => {
                    toast.success('版本已发布', {
                      description: '草稿已冻结,下次修改将开新草稿',
                    })
                    setShowPublish(false)
                    setPublishChangelog('')
                  },
                  onError: () =>
                    toast.warning('已发布,但标记就绪失败(可能已有发布版本)'),
                })
              },
              onError: () => toast.error('发布失败,请重试'),
            },
          )
        },
        onError: () => toast.error('更新草稿失败,请重试'),
      },
    )
  }

  function handleBacktest() {
    navigate('/backtest')
  }

  function handleNewStrategy() {
    // honest:新建策略占位 toast(无新建 modal,TD-037)
    toast.info('新建策略功能待实现', { description: '从空白草稿开始' })
  }

  // 状态按钮(DRAFT/READY/RUNNING/PAUSED/STOPPED/ERROR 6 态映射原型 4 态)
  const status = selected?.status
  function renderStatusButton() {
    if (!selected) return null
    if (status === 'DRAFT') {
      return (
        <Button variant="ghost" size="sm" onClick={() => toast.warning('需要先发布代码', { description: '草稿策略无法直接启动' })}>
          <Play className="size-3.5" aria-hidden /> 启动
        </Button>
      )
    }
    if (status === 'RUNNING') {
      return (
        <Button variant="ghost" size="sm" onClick={() => setPauseTarget(selected)}>
          <Square className="size-3.5" aria-hidden /> 暂停
        </Button>
      )
    }
    if (status === 'PAUSED' || status === 'READY') {
      return (
        <Button size="sm" onClick={() => setShowStart(true)}>
          <Play className="size-3.5" aria-hidden /> 启动
        </Button>
      )
    }
    if (status === 'ERROR') {
      return (
        <Button variant="ghost" size="sm" onClick={() => toast.warning('策略异常', { description: 'Worker 运行出错,请检查日志' })}>
          <AlertTriangle className="size-3.5" aria-hidden /> 异常
        </Button>
      )
    }
    // STOPPED(终态)
    return (
      <Button variant="ghost" size="sm" onClick={() => toast.warning('已停止', { description: '需重新编辑回草稿' })}>
        已停止
      </Button>
    )
  }

  return (
    <div className="flex flex-col gap-4.5">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex flex-wrap items-center gap-2.5">
            <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">
              {selected?.name ?? '…'}
            </h1>
            {selected && <StrategyStatusBadge status={selected.status.toLowerCase()} />}
            <Chip label={latestVersion ? `v${latestVersion}` : 'v?'} />
            <Chip color="info" label={selected?.symbol ?? ''} />
            <Chip label={selected?.exchange ?? ''} />
            <Chip label={selected?.intervalValue ?? ''} />
          </div>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            {selected?.description ?? ''} · {codeLines ?? '—'} 行 · 更新于{' '}
            {selected ? formatDateTime(selected.updatedAt) : '—'}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="ghost" size="sm" onClick={handleBacktest}>
            <History className="size-3.5" aria-hidden /> 跑回测
          </Button>
          {renderStatusButton()}
          {selected &&
            (selected.status === 'RUNNING' ||
              selected.status === 'PAUSED' ||
              selected.status === 'ERROR') && (
              <Button
                variant="ghost"
                size="sm"
                className="text-down hover:text-down"
                onClick={() => setStopTarget(selected)}
              >
                <Square className="size-3.5" aria-hidden /> 停止
              </Button>
            )}
          <Button size="sm" onClick={() => setShowPublish(true)} disabled={!draftCodeId}>
            <GitBranch className="size-3.5" aria-hidden /> 发布版本
          </Button>
        </div>
      </div>

      {/* Strategy list rail */}
      <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1">
        {listError ? (
          <ErrorState />
        ) : listLoading ? (
          <LoadingState />
        ) : !strategies || strategies.length === 0 ? (
          <EmptyState title="暂无策略" description="新建第一个策略开始编码。" />
        ) : (
          <>
            {strategies.map((s) => {
              const isActive = s.id === effectiveSelectedId
              return (
                <button
                  key={s.id}
                  onClick={() => setSelectedId(s.id)}
                  className={`flex flex-[0_0_220px] flex-col rounded-lg border p-3 text-left transition ${
                    isActive
                      ? 'border-accent bg-accent-soft'
                      : 'border-border-soft bg-surface-card hover:bg-surface-card-2'
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <strong className="truncate text-body-sm text-text-primary">{s.name}</strong>
                    <StrategyStatusBadge status={s.status.toLowerCase()} />
                  </div>
                  <div className="mt-1 text-[11px] text-text-muted">
                    {s.symbol} · {s.intervalValue}
                  </div>
                  <div className="kq-mono-row mt-1.5 text-body-sm font-bold text-text-muted">— USDT</div>
                </button>
              )
            })}
            <button
              onClick={handleNewStrategy}
              className="flex flex-[0_0_220px] items-center justify-center rounded-lg border border-dashed border-border-soft text-body-sm text-text-muted transition hover:bg-surface-card-2"
            >
              <Plus className="mr-1 size-3.5" aria-hidden /> 新建策略
            </button>
          </>
        )}
      </div>

      {/* Main grid: code + AI chat */}
      <div className="grid gap-4.5 [grid-template-columns:1.4fr_1fr] max-[1100px]:[grid-template-columns:1fr]">
        {/* code editor card */}
        <div className="overflow-hidden rounded-lg border border-border-soft bg-surface-card shadow-card">
          <div className="flex flex-wrap items-center justify-between gap-2.5 border-b border-border-soft px-3.5 py-2.5">
            <div className="flex gap-1.5">
              {CODE_TABS.map((t) => (
                <button
                  key={t}
                  onClick={() => setActiveTab(t)}
                  className={`rounded-md px-2.5 py-1 text-caption font-medium transition ${
                    activeTab === t
                      ? 'bg-surface-card-2 text-text-primary'
                      : 'text-text-muted hover:bg-surface-card-2'
                  }`}
                >
                  {t}
                </button>
              ))}
            </div>
            <div className="flex items-center gap-2.5 text-[11px] text-text-muted">
              <span>
                {!draftCodeId ? '模板预览(不自动保存)' : saveStatus === 'saving' ? '保存中…' : saveStatus === 'dirty' ? '未保存' : '● 已保存'}
              </span>
              <span>·</span>
              <span>自动保存 3s</span>
              <span className="h-3 w-px bg-border-soft" />
              <button
                onClick={() => setShowVersions(true)}
                className="inline-flex items-center gap-1.5 rounded-md border border-border-soft bg-surface-card-2 px-2.5 py-1 text-[11px] font-medium text-text-secondary transition hover:bg-surface-3"
                title="查看代码版本时间线"
              >
                <GitBranch className="size-3" aria-hidden /> 版本
                <Chip color="info" label={`${codes?.length ?? 0} 态`} />
              </button>
              <button
                onClick={() => setShowFSM(true)}
                className="inline-flex items-center gap-1.5 rounded-md border border-border-soft bg-surface-card-2 px-2.5 py-1 text-[11px] font-medium text-text-secondary transition hover:bg-surface-3"
                title="查看策略状态机说明"
              >
                <FileCode className="size-3" aria-hidden /> 状态机
              </button>
            </div>
          </div>
          <div className="relative">
            {activeTab !== 'onBar.py' ? (
              <div className="flex h-[460px] items-center justify-center text-text-muted">
                <EmptyState title={activeTab} description="本文件暂不可编辑。" />
              </div>
            ) : codeLoading ? (
              <div className="flex h-[460px] items-center justify-center">
                <LoadingState />
              </div>
            ) : (
              <Editor
                key={draftCodeId ?? 'template'}
                height={460}
                defaultLanguage="python"
                theme="vs-dark"
                defaultValue={codeDetail?.sourceCode ?? STRATEGY_TEMPLATE}
                onChange={(val) => handleCodeChange(val)}
                options={{
                  minimap: { enabled: false },
                  fontSize: 12.5,
                  lineNumbers: 'on',
                  scrollBeyondLastLine: false,
                  tabSize: 2,
                  automaticLayout: true,
                }}
              />
            )}
          </div>
        </div>

        {/* AI chat */}
        <div>{selected && <AIChat strategy={selected} version={latestVersion} />}</div>
      </div>

      {/* ─── Publish modal ─── */}
      <Dialog open={showPublish} onOpenChange={setShowPublish}>
        <DialogContent className="max-w-[520px]">
          <DialogHeader>
            <DialogTitle>发布代码版本</DialogTitle>
            <DialogDescription>发布即冻结,要改需开新草稿。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3.5">
            <div>
              <Label className="kq-label">版本号</Label>
              <Input
                value={publishVersion}
                onChange={(e) => setPublishVersion(e.target.value)}
                placeholder={`v${latestVersion ? latestVersion + 1 : 1}`}
              />
            </div>
            <div>
              <Label className="kq-label">变更说明</Label>
              <Textarea
                value={publishChangelog}
                onChange={(e) => setPublishChangelog(e.target.value)}
                placeholder="加入 ADX>25 趋势过滤,止损 ATR×1.5 → ATR×2.5"
                className="min-h-[80px]"
              />
            </div>
            <div className="rounded-md border border-dashed border-border-soft bg-surface-card-2 p-3 text-caption leading-relaxed text-text-secondary">
              <strong className="text-warning">⚠ 一旦发布即冻结</strong>,不可再修改。要改需开新草稿,当前已发布版本将自动归档。
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowPublish(false)}>
              取消
            </Button>
            <Button onClick={handlePublish} disabled={publishMut.isPending || readyMut.isPending}>
              {publishMut.isPending || readyMut.isPending ? '发布中…' : '发布并冻结'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ─── Start modal ─── */}
      <Dialog open={showStart} onOpenChange={setShowStart}>
        <DialogContent className="max-w-[460px]">
          <DialogHeader>
            <DialogTitle>启动策略</DialogTitle>
            <DialogDescription>Worker 上线接收行情并按策略下单。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div className="rounded-md border border-border-soft bg-surface-card-2 p-3.5">
              <div className="text-body-sm font-semibold text-text-primary">{selected?.name}</div>
              <div className="mt-1 text-[11px] text-text-muted">
                {selected?.symbol} · {selected?.exchange} · {selected?.intervalValue}
              </div>
            </div>
            <div className="text-caption leading-relaxed text-text-secondary">
              启动后 Worker 将自动接收行情并按策略下单。绑定账户:
            </div>
            <Select value={startAccount} onValueChange={setStartAccount}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="PAPER">PAPER · 主模拟盘</SelectItem>
                <SelectItem value="LIVE">LIVE · 主账户(需二次确认)</SelectItem>
              </SelectContent>
            </Select>
            <div className="rounded-md border border-accent bg-accent-soft p-2.5 text-[11px] leading-relaxed text-text-primary">
              ⚠ 启动到 LIVE 账户需高风险二次确认,会触发风控闸门检查。
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowStart(false)}>
              取消
            </Button>
            <Button onClick={handleStart} disabled={startMut.isPending}>
              <Play className="size-3.5" aria-hidden /> {startMut.isPending ? '启动中…' : '启动'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ─── Versions modal ─── */}
      <Dialog open={showVersions} onOpenChange={setShowVersions}>
        <DialogContent className="max-w-[560px]">
          <DialogHeader>
            <DialogTitle>代码版本</DialogTitle>
            <DialogDescription>当前策略 · {selected?.name}</DialogDescription>
          </DialogHeader>
          <div className="mb-3 flex items-center justify-between">
            <div className="text-caption text-text-secondary">
              倒序展示 · 共 {codes?.length ?? 0} 个版本
            </div>
            <Chip color="info" label="3 态:草稿 / 已发布 / 已归档" />
          </div>
          <div className="flex flex-col gap-2">
            {(codes ?? []).map((c) => (
              <VersionRow key={c.id} c={c} />
            ))}
            {(!codes || codes.length === 0) && (
              <div className="py-4 text-center text-text-muted">暂无代码版本</div>
            )}
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowVersions(false)}>
              关闭
            </Button>
            <Button onClick={() => { setShowVersions(false); setShowPublish(true) }}>
              <GitBranch className="size-3.5" aria-hidden /> 发布新版本
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ─── FSM modal ─── */}
      <Dialog open={showFSM} onOpenChange={setShowFSM}>
        <DialogContent className="max-w-[560px]">
          <DialogHeader>
            <DialogTitle>策略状态机说明</DialogTitle>
            <DialogDescription>策略状态流转规则与 LIVE 二次确认说明。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3.5">
            <div>
              <div className="mb-2 text-[11px] uppercase tracking-[0.04em] text-text-muted">STATE FLOW</div>
              <div className="flex flex-wrap items-center gap-1.5 text-caption">
                {['草稿', '就绪', '运行中', '已暂停', '已停止'].map((s, i, arr) => (
                  <span key={s} className="flex items-center gap-1.5">
                    <span
                      className={`rounded-md border px-2.5 py-1 text-[11px] font-medium ${
                        s === '运行中'
                          ? 'border-accent bg-accent-soft text-accent'
                          : 'border-border-soft bg-surface-card-2 text-text-secondary'
                      }`}
                    >
                      {s}
                    </span>
                    {i < arr.length - 1 && <span className="text-text-muted">→</span>}
                  </span>
                ))}
              </div>
            </div>
            <div className="rounded-md bg-surface-card-2 p-3 text-caption leading-relaxed text-text-secondary">
              <div className="mb-1.5 font-semibold text-text-primary">流转规则</div>· <strong>草稿 → 就绪</strong>:需先发布代码版本,发布即冻结<br />
              · <strong>就绪 → 运行中</strong>:Worker 上线接收行情并按策略下单<br />
              · <strong>运行中 ⇄ 已暂停</strong>:不停进程,只标记不下单<br />
              · <strong>已停止</strong>:终态,需重新编辑回草稿
            </div>
            <div className="rounded-md border border-accent bg-accent-soft p-3 text-[11px] leading-relaxed text-text-primary">
              ⚠ 切到 LIVE 账户需高风险二次确认,会触发风控闸门检查。
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowFSM(false)}>
              关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ─── 暂停/停止 Confirm ─── */}
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
    </div>
  )
}

// ─── 版本行 ───
function VersionRow({ c }: { c: StrategyCodeDto }) {
  const isDraft = c.status === 'DRAFT'
  const isPublished = c.status === 'PUBLISHED'
  return (
    <div
      className={`flex items-center gap-2.5 rounded-md border p-3 ${
        isDraft ? 'border-accent bg-accent-soft' : 'border-transparent bg-surface-card-2'
      }`}
    >
      <span
        className={`size-2.5 shrink-0 rounded-full border-2 ${
          isDraft
            ? 'border-accent'
            : isPublished
              ? 'border-up'
              : 'border-text-muted'
        }`}
      />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-caption font-semibold text-text-primary">v{c.versionNumber}</span>
          {isDraft && <Chip color="accent" label="DRAFT" />}
          {isPublished && <Chip color="up" label="PUBLISHED" />}
          {!isDraft && !isPublished && <Chip label="ARCHIVED" />}
        </div>
        <div className="mt-0.5 text-[11px] text-text-muted">{c.changelog}</div>
      </div>
      <div className="text-[10px] text-text-muted">{formatDateTime(c.updatedAt)}</div>
    </div>
  )
}
