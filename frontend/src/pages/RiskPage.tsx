import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Info, AlertTriangle, OctagonX, Plus, Pencil, Trash2, Sparkles } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  EmptyRow,
  LoadingRow,
} from '@/components/ui/table'
import { SectionTitle } from '@/components/SectionTitle'
import { Chip } from '@/components/Chip'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { useRiskPolicies, useRiskDecisions, useToggleRiskPolicy, useDeleteRiskPolicy } from '@/hooks/useRisk'
import { PolicyEditModal } from '@/components/PolicyEditModal'
import { AiRuleDialog } from '@/components/AiRuleDialog'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ButtonIcon } from '@/components/ButtonIcon'
import { useStrategies } from '@/hooks/useStrategies'
import { useAccounts } from '@/hooks/useAccounts'
import { stopStrategy } from '@/api/strategy'
import { strategyKeys } from '@/api/_queryKeys'
import { formatRuleValue, ruleDesc, ruleInitial, RULE_LABEL, type RuleType } from '@/lib/risk'
import { formatDateTime } from '@/lib/format'
import type { components } from '@/types/api-gen'

/**
 * RiskPage — 风控页(照原型 done-design/components/RiskPage.jsx port)。
 *
 * 适配后端契约:
 *  - 规则 → RiskPolicyDto[](useRiskPolicies),toggle 走 PATCH /toggle(乐观更新)
 *  - 审计 → RiskDecisionDto[](useRiskDecisions),verdict APPROVED/REJECTED
 *  - 紧急停止 → 批量 stopStrategy(Promise.allSettled)，后端无"紧急停止"端点(前端批量映射)
 * 金额:params.maxNotionalUsdt/maxLossUsdt 全 toDecimal + formatMoney，展示全 kq-mono-row。
 * 图标全 lucide-react(Info/AlertTriangle/Download/OctagonX)，不用 emoji(ⓘ⚠↓⏹)。
 * 破坏性操作：紧急停止双 modal + STOP 文本校验(原型无校验，移植按 CLAUDE.md 加)。
 */
type RiskPolicyDto = components['schemas']['RiskPolicyDto']
type RiskDecisionDto = components['schemas']['RiskDecisionDto']

export function RiskPage() {
  const [showStop, setShowStop] = useState(false)
  const [showStopConfirm, setShowStopConfirm] = useState(false)
  const [stopText, setStopText] = useState('')
  const [isStopping, setIsStopping] = useState(false)
  const [editPolicy, setEditPolicy] = useState<RiskPolicyDto | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [aiDialogOpen, setAiDialogOpen] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<RiskPolicyDto | null>(null)
  const deletePolicy = useDeleteRiskPolicy()

  const queryClient = useQueryClient()
  const { data: policies, isLoading, error, refetch } = useRiskPolicies()
  const { data: strategies } = useStrategies()
  const { data: accounts } = useAccounts()

  const running = (strategies ?? []).filter((s) => s.status === 'RUNNING')
  // AuditRow 用：按 accountId 查 paperTrading(RiskDecisionDto 无 paperTrading 字段)
  const paperIds = new Set((accounts ?? []).filter((a) => a.paperTrading).map((a) => a.id))
  // accounts 未 ready(loading/error)时 paperIds 空，AuditRow 显 #id unknown 避免误标实盘(fail-closed)
  const accountsLoaded = accounts != null
  // AuditRow 标"内置"用：按 accountId 聚合已配 ruleType
  // (决策有 MAX_INITIAL_MARGIN 但该账户未配 → 后端 PERP 80% 兜底，见 RiskService.evaluate)
  const accountRuleTypes = new Map<number, Set<string>>()
  ;(policies ?? []).forEach((p) => {
    const set = accountRuleTypes.get(p.accountId) ?? new Set()
    set.add(p.ruleType)
    accountRuleTypes.set(p.accountId, set)
  })

  /** 紧急停止执行：批量 POST /stop,Promise.allSettled 收集失败，toast 报 N 停止·M 失败。
   * 执行期保留 Modal 2 开启 + isStopping 锁按钮 + "停止中…" 文案，完成后再关 modal。 */
  const handleEmergencyStop = async () => {
    setIsStopping(true)
    try {
      const results = await Promise.allSettled(running.map((s) => stopStrategy(s.id)))
      const failed = results.filter((r) => r.status === 'rejected').length
      const stopped = results.length - failed
      toast.warning(
        `紧急停止已执行:${stopped} 个策略已停止${failed > 0 ? ` · ${failed} 个失败` : ''}`,
      )
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
      setShowStopConfirm(false)
      setStopText('')
    } finally {
      setIsStopping(false)
    }
  }

  if (error) {
    return <ErrorState message="暂时无法加载风控策略，请稍后重试" onRetry={() => refetch()} />
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <div>
          <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">风控</h1>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            下单前自动检查 · 防超额 / 防爆仓 / 防滥用
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => setAiDialogOpen(true)} title="用自然语言描述风控要求，AI 解析后确认保存">
            <Sparkles className="size-4" aria-hidden />
            一句话建规则
          </Button>
          <Button variant="ghost" size="sm" onClick={() => { setEditPolicy(null); setModalOpen(true) }}>
            <Plus className="size-4" aria-hidden />
            新建规则
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setShowStop(true)} disabled={running.length === 0}>
            <OctagonX className="size-4" aria-hidden />
            {running.length === 0 ? '无运行中策略' : '紧急停止'}
          </Button>
        </div>
      </div>

      {/* Behavior banner */}
      <Card className="border-dashed border-border-soft bg-surface-card-2 px-6 py-5">
        <div className="flex items-start gap-3.5">
          <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent">
            <Info className="size-[18px]" aria-hidden />
          </div>
          <div className="text-caption leading-[1.6] text-text-secondary">
            <strong className="text-text-primary">风控行为</strong> · 每次下单前检查已启用的规则，超过阈值时拒绝下单，并说明触发原因。风控只能限制预设范围内的风险，不能替代持仓监控。
          </div>
        </div>
      </Card>

      {/* Rules grid */}
      <div className="grid grid-cols-3 gap-3.5 max-[900px]:grid-cols-1">
        {isLoading
          ? <Card className="col-span-3 p-6"><LoadingState rows={3} /></Card>
          : (policies ?? []).length === 0
            ? <Card className="col-span-3"><EmptyState title="还没有自定义规则" description="合约内置的 80% 保证金占用规则会自动生效。其他规则未配置时不会限制下单，建议按账户设置额度和频率限制。" action={<div className="flex justify-center gap-2"><Button size="sm" variant="outline" onClick={() => setAiDialogOpen(true)}><Sparkles className="size-3.5" aria-hidden />一句话建规则</Button><Button size="sm" onClick={() => { setEditPolicy(null); setModalOpen(true) }}>新建规则</Button></div>} /></Card>
            : (policies ?? []).map((p) => <RuleCard key={p.id} policy={p} onEdit={(policy) => { setEditPolicy(policy); setModalOpen(true) }} onDelete={setDeleteTarget} />)}
      </div>

      {/* Audit table */}
      <AuditTable paperIds={paperIds} accountsLoaded={accountsLoaded} accountRuleTypes={accountRuleTypes} />

      {/* 策略编辑/新建 modal(key 重置触发 re-mount，避免 setState-in-effect) */}
      <PolicyEditModal
        key={modalOpen ? (editPolicy?.id ?? 'create') : 'closed'}
        mode={editPolicy ? 'edit' : 'create'}
        policy={editPolicy}
        policies={policies ?? []}
        open={modalOpen}
        onOpenChange={setModalOpen}
      />
      {/* 一句话建规则:解析预览 → 确认落库，key 重挂载同款范式 */}
      <AiRuleDialog
        key={aiDialogOpen ? 'open' : 'closed'}
        policies={policies ?? []}
        open={aiDialogOpen}
        onOpenChange={setAiDialogOpen}
      />
      {/* 删除规则确认 */}
      <ConfirmDialog
        open={deleteTarget != null}
        onOpenChange={(o) => { if (!o) setDeleteTarget(null) }}
        title="删除规则"
        description={deleteTarget ? `确认删除「${deleteTarget.name}」?删除后该账户此规则不再生效。` : ''}
        destructive
        confirmLabel="删除"
        loading={deletePolicy.isPending}
        onConfirm={() => {
          if (!deleteTarget) return
          const id = deleteTarget.id
          deletePolicy.mutate(id, {
            onSuccess: () => { toast.success('规则已删除'); setDeleteTarget(null) },
            onError: () => toast.error('删除失败，请重试'),
          })
        }}
      />

      {/* 紧急停止 Modal 1 — 警告 + 运行中策略列表 */}
      <Dialog open={showStop} onOpenChange={setShowStop}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle className="text-h3">紧急停止 · 高风险操作</DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            {/* 警告红框 */}
            <div className="rounded-lg border border-down bg-down/10 p-3.5">
              <div className="flex items-center gap-1.5 text-body-sm font-bold text-down">
                <AlertTriangle className="size-4" aria-hidden />
                将尝试停止所有运行中策略
              </div>
              <div className="mt-1 text-caption-sm leading-[1.5] text-text-secondary">
                停止策略不会自动撤销挂单或平仓。部分策略可能因网络原因停止失败，请在操作后检查通知、挂单和持仓。
              </div>
            </div>
            {/* 运行中策略列表 */}
            <div className="rounded-lg border border-border-soft bg-surface-card-2 p-3.5">
              <div className="text-caption text-text-muted">
                将停止以下 {running.length} 个运行中策略：
              </div>
              <div className="mt-2 flex flex-col gap-1.5">
                {running.map((s) => (
                  <div key={s.id} className="flex justify-between text-caption">
                    <span>{s.name}</span>
                    <span className="text-text-muted">{s.symbol}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" size="sm" onClick={() => setShowStop(false)}>
              取消
            </Button>
            <Button
              variant="destructive"
              size="sm"
              onClick={() => {
                setShowStop(false)
                setShowStopConfirm(true)
              }}
            >
              下一步 →
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 紧急停止 Modal 2 — 二次确认 + STOP 输入 */}
      <Dialog open={showStopConfirm} onOpenChange={setShowStopConfirm}>
        <DialogContent className="sm:max-w-[440px]">
          <DialogHeader>
            <DialogTitle>二次确认</DialogTitle>
          </DialogHeader>
          <div className="rounded-lg border border-accent bg-accent-soft p-3.5 text-caption leading-[1.55] text-accent-warm">
            <strong>这是高风险操作的二次确认流程。</strong>
            <br />
            输入「STOP」以确认停止所有运行中策略。停止结果会在通知中列出。
          </div>
          <Input
            placeholder="输入 STOP 确认"
            value={stopText}
            onChange={(e) => setStopText(e.target.value)}
          />
          <DialogFooter>
            <Button variant="ghost" size="sm" onClick={() => setShowStopConfirm(false)}>
              取消
            </Button>
            {/* 破坏性操作：按钮 disabled 直到 stopText === 'STOP'(原型无此校验，移植按 CLAUDE.md 加)。
                执行期 isStopping 锁按钮 + "停止中…" 文案，避免 fetch 期间页面静默。 */}
            <Button
              variant="destructive"
              size="sm"
              disabled={isStopping || stopText !== 'STOP'}
              onClick={handleEmergencyStop}
            >
              {isStopping ? '停止中…' : '确认停止全部'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** RuleCard — 单条风控规则卡(照原型 RuleCard 抄)。 */
function RuleCard({ policy, onEdit, onDelete }: { policy: RiskPolicyDto; onEdit: (p: RiskPolicyDto) => void; onDelete: (p: RiskPolicyDto) => void }) {
  const toggle = useToggleRiskPolicy()
  const { name, ruleType, params, enabled } = policy

  const handleToggle = (checked: boolean) => {
    toggle.mutate(
      { policyId: policy.id, enabled: checked },
      {
        onSuccess: (updated) => {
          if (updated.enabled) {
            toast.success(`${updated.name} 已启用`)
          } else {
            toast.warning(`${updated.name} 已停用`)
          }
        },
        onError: () => {
          toast.error('启停失败，请重试')
        },
      },
    )
  }

  return (
    <Card className="p-5">
      <div className="flex items-start justify-between">
        <div className="flex-1">
          {/* icon + name + ruleType */}
          <div className="flex items-center gap-2">
            <div className="flex size-8 items-center justify-center rounded-lg bg-accent-soft font-mono text-body-sm font-bold text-accent">
              {ruleInitial(ruleType)}
            </div>
            <div>
              <div className="text-body font-bold text-text-primary">{name}</div>
            </div>
          </div>
          {/* desc */}
          <div className="mt-2.5 text-caption leading-[1.5] text-text-secondary">
            {ruleDesc(ruleType)}
          </div>
          {/* 当前阈值 */}
          <div className="mt-2.5 rounded-lg bg-surface-card-2 px-2.5 py-2">
            <div className="text-caption-xs uppercase tracking-[0.04em] text-text-muted">
              当前阈值
            </div>
            <div className="kq-mono-row mt-0.5 text-kpi-sm font-bold text-accent">
              {formatRuleValue(ruleType, params)}
            </div>
          </div>
          {/* 说明文(删实现细节：原"脱敏""fail-closed"是后端术语，改用户语言) */}
          <div className="mt-2 text-caption-sm leading-[1.5] text-text-muted">
            · 拒单原因会说明触发哪条规则
            <br />
            · 未配置规则时下单不受限制
          </div>
        </div>
        {/* actions: 编辑/删除/启停 */}
        <div className="flex flex-col items-center gap-1.5">
          <div className="flex gap-0.5">
            <ButtonIcon variant="ghost" size="sm" label="编辑" onClick={() => onEdit(policy)}>
              <Pencil className="size-3.5" aria-hidden />
            </ButtonIcon>
            <ButtonIcon variant="ghost" size="sm" label="删除" onClick={() => onDelete(policy)}>
              <Trash2 className="size-3.5" aria-hidden />
            </ButtonIcon>
          </div>
          <Switch
            checked={enabled}
            disabled={toggle.isPending}
            onCheckedChange={handleToggle}
            aria-label={`${name} 启停`}
          />
          <span className="text-caption-xs text-text-muted">{enabled ? '开' : '关'}</span>
        </div>
      </div>
    </Card>
  )
}

/** AuditTable — 决策审计表(照原型 AuditTable 抄)。 */
function AuditTable({ paperIds, accountsLoaded, accountRuleTypes }: { paperIds: Set<number>; accountsLoaded: boolean; accountRuleTypes: Map<number, Set<string>> }) {
  const { data, isLoading, error, refetch } = useRiskDecisions({ page: 1, pageSize: 50 })

  const decisions = data?.content ?? []

  return (
    <Card className="overflow-hidden p-0">
      <div className="px-6 pt-6">
        <SectionTitle
          title="决策审计"
          sub="每次风控决策的记录"
        />
      </div>
      <div className="overflow-auto">
        <Table>
          <TableHeader>
            <TableRow className="text-left text-caption-xs uppercase tracking-[0.04em] text-text-muted">
              <TableHead className="kq-sticky-col px-3 py-2">时间</TableHead>
              <TableHead className="px-3 py-2">规则</TableHead>
              <TableHead className="px-3 py-2">决策</TableHead>
              <TableHead className="px-3 py-2">详情</TableHead>
              <TableHead className="px-3 py-2">账户</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="kq-mono-row">
            {error ? (
              <EmptyRow colSpan={5}>
                <ErrorState
                  message="暂时无法加载决策审计，请稍后重试"
                  onRetry={() => refetch()}
                />
              </EmptyRow>
            ) : isLoading ? (
              <LoadingRow colSpan={5}>
                <LoadingState rows={4} />
              </LoadingRow>
            ) : decisions.length === 0 ? (
              <EmptyRow colSpan={5}>
                <EmptyState title="无决策记录" description="暂无风控决策日志" />
              </EmptyRow>
            ) : (
              decisions.map((d) => <AuditRow key={d.id} d={d} paperIds={paperIds} accountsLoaded={accountsLoaded} accountRuleTypes={accountRuleTypes} />)
            )}
          </TableBody>
        </Table>
      </div>
    </Card>
  )
}

/** AuditRow — 单行决策审计(照原型 tr 抄)。 */
function AuditRow({ d, paperIds, accountsLoaded, accountRuleTypes }: { d: RiskDecisionDto; paperIds: Set<number>; accountsLoaded: boolean; accountRuleTypes: Map<number, Set<string>> }) {
  const verdict = d.verdict
  const approved = verdict === 'APPROVED'
  // ruleResults[0].ruleType(照原型 rule 列)+ 中文短名
  const ruleType = d.ruleResults[0]?.ruleType ?? '—'
  // reason:APPROVED 时为 null(契约"通过时为 null")→ 显示 —
  const reason = d.ruleResults[0]?.reason ?? '—'
  // 内置默认：决策有 MAX_INITIAL_MARGIN 但该账户未配(后端 PERP 80% 兜底)
  const isBuiltin = ruleType === 'MAX_INITIAL_MARGIN' && !accountRuleTypes.get(d.accountId ?? 0)?.has('MAX_INITIAL_MARGIN')

  return (
    <TableRow className="border-b border-border-soft">
      <TableCell className="kq-sticky-col px-3 py-2.5">{formatDateTime(d.createdAt)}</TableCell>
      <TableCell className="px-3 py-2.5">
        <Chip label={RULE_LABEL[ruleType as RuleType] ?? ruleType} />
        {isBuiltin && <span className="ml-1 align-middle text-caption-xs text-text-muted">内置</span>}
      </TableCell>
      <TableCell className="px-3 py-2.5">
        <span
          className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-caption-sm font-bold ${approved ? 'bg-up/15 text-up' : 'bg-down/15 text-down'}`}
        >
          {approved ? '✓' : '✕'} {approved ? '放行' : '拒绝'}
        </span>
      </TableCell>
      <TableCell className="px-3 py-2.5 text-text-secondary">{reason}</TableCell>
      <TableCell className="px-3 py-2.5">
        {!accountsLoaded ? (
          <span className="text-text-muted">#{d.accountId}</span>
        ) : d.accountId != null && paperIds.has(d.accountId) ? (
          <span className="kq-paper-badge">模拟</span>
        ) : (
          <span className="kq-live-badge">实盘</span>
        )}
      </TableCell>
    </TableRow>
  )
}

// 注:StrategyDetailDto 类型导入仅用于 running filter 的类型推导，不单独使用其字段。
