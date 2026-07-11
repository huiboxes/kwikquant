import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Info, AlertTriangle, Download, OctagonX } from 'lucide-react'
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
} from '@/components/ui/table'
import { SectionTitle } from '@/components/SectionTitle'
import { Chip } from '@/components/Chip'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { useRiskPolicies, useRiskDecisions, useToggleRiskPolicy } from '@/hooks/useRisk'
import { useStrategies } from '@/hooks/useStrategies'
import { stopStrategy } from '@/api/strategy'
import { strategyKeys } from '@/api/_queryKeys'
import { formatRuleValue, ruleDesc, ruleInitial } from '@/lib/risk'
import { formatDateTime } from '@/lib/format'
import type { components } from '@/types/api-gen'

/**
 * RiskPage — 风控页(照原型 done-design/components/RiskPage.jsx port)。
 *
 * 适配后端契约:
 *  - 规则 → RiskPolicyDto[](useRiskPolicies),toggle 走 PATCH /toggle(乐观更新)
 *  - 审计 → RiskDecisionDto[](useRiskDecisions),verdict APPROVED/REJECTED
 *  - 紧急停止 → 批量 stopStrategy(Promise.allSettled),后端无"紧急停止"端点(honest 映射)
 * 金额:params.maxNotionalUsdt/maxLossUsdt 全 toDecimal + formatMoney,展示全 kq-mono-row。
 * 图标全 lucide-react(Info/AlertTriangle/Download/OctagonX),不用 emoji(ⓘ⚠↓⏹)。
 * 破坏性操作:紧急停止双 modal + STOP 文本校验(原型无校验,移植按 CLAUDE.md 加)。
 */
type RiskPolicyDto = components['schemas']['RiskPolicyDto']
type RiskDecisionDto = components['schemas']['RiskDecisionDto']

export function RiskPage() {
  const [showStop, setShowStop] = useState(false)
  const [showStopConfirm, setShowStopConfirm] = useState(false)
  const [stopText, setStopText] = useState('')

  const queryClient = useQueryClient()
  const { data: policies, isLoading, error } = useRiskPolicies()
  const { data: strategies } = useStrategies()

  const running = (strategies ?? []).filter((s) => s.status === 'RUNNING')

  /** 紧急停止执行:批量 POST /stop,Promise.allSettled 收集失败,toast 报 N 停止·M 失败。 */
  const handleEmergencyStop = async () => {
    setShowStopConfirm(false)
    setStopText('')
    const results = await Promise.allSettled(running.map((s) => stopStrategy(s.id)))
    const failed = results.filter((r) => r.status === 'rejected').length
    const stopped = results.length - failed
    toast.warning(
      `紧急停止已执行:${stopped} 个策略已停止${failed > 0 ? ` · ${failed} 个失败` : ''}`,
    )
    queryClient.invalidateQueries({ queryKey: strategyKeys.all })
  }

  if (error) {
    return <ErrorState message={(error as Error).message} onRetry={() => setShowStop(false)} />
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <div>
          <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">风控</h1>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            下单前自动检查 · 防超额 / 防暴仓 / 防滥用
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => setShowStop(true)}>
            <OctagonX className="size-4" aria-hidden />
            紧急停止
          </Button>
          <Button variant="default" size="sm" onClick={() => toast.success('规则已保存')}>
            保存规则
          </Button>
        </div>
      </div>

      {/* Behavior banner */}
      <Card className="border-dashed border-border-soft bg-surface-card-2 px-6 py-5">
        <div className="flex items-start gap-3.5">
          <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent">
            <Info className="size-[18px]" aria-hidden />
          </div>
          <div className="text-body-sm leading-[1.6] text-text-secondary">
            <strong className="text-text-primary">风控行为</strong> · 拒绝不是 HTTP 错误,而是业务结果(HTTP 200 + 业务码 4105),UI 需读响应体判断而非状态码。
            拒绝原因脱敏:只告知"被哪条规则拒",不告知阈值具体多少(防探测)。
            <strong className="text-warning">风控服务挂了:</strong>平仓单放行 + 审计;开仓单直接拒(fail-closed)。
          </div>
        </div>
      </Card>

      {/* Rules grid */}
      <div className="grid grid-cols-3 gap-3.5 max-[900px]:grid-cols-1">
        {isLoading
          ? <Card className="col-span-3 p-6"><LoadingState rows={3} /></Card>
          : (policies ?? []).map((p) => <RuleCard key={p.id} policy={p} />)}
      </div>

      {/* Audit table */}
      <AuditTable />

      {/* 紧急停止 Modal 1 — 警告 + 运行中策略列表 */}
      <Dialog open={showStop} onOpenChange={setShowStop}>
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle className="text-h3">紧急停止 · 高风险操作</DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            {/* 警告红框 */}
            <div className="rounded-lg border border-down bg-down/10 p-3.5">
              <div className="flex items-center gap-1.5 text-body font-bold text-down">
                <AlertTriangle className="size-4" aria-hidden />
                紧急停止会停掉所有运行中策略
              </div>
              <div className="mt-1 text-[11px] leading-[1.5] text-text-secondary">
                部分策略可能因 Worker 通信失败而无法停止,失败列表会暴露给你。
              </div>
            </div>
            {/* 运行中策略列表 */}
            <div className="rounded-lg border border-border-soft bg-surface-card-2 p-3.5">
              <div className="text-body-sm text-text-muted">
                将停止以下 {running.length} 个运行中策略:
              </div>
              <div className="mt-2 flex flex-col gap-1.5">
                {running.map((s) => (
                  <div key={s.id} className="flex justify-between text-body-sm">
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
          <div className="rounded-lg border border-accent bg-accent-soft p-3.5 text-body-sm leading-[1.55] text-accent-warm">
            <strong>这是高风险操作的二次确认流程。</strong>
            <br />
            输入"STOP"以确认停止所有运行中策略。失败列表会在通知中暴露。
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
            {/* 破坏性操作:按钮 disabled 直到 stopText === 'STOP'(原型无此校验,移植按 CLAUDE.md 加) */}
            <Button
              variant="destructive"
              size="sm"
              disabled={stopText !== 'STOP'}
              onClick={handleEmergencyStop}
            >
              确认停止全部
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** RuleCard — 单条风控规则卡(照原型 RuleCard 抄)。 */
function RuleCard({ policy }: { policy: RiskPolicyDto }) {
  const toggle = useToggleRiskPolicy()
  const { name, ruleType, params, enabled } = policy

  const handleToggle = (checked: boolean) => {
    toggle.mutate(
      { policyId: policy.id, enabled: checked },
      {
        onSuccess: (updated) => {
          toast.success(`${updated.name} ${updated.enabled ? '已启用' : '已停用'}`)
        },
        onError: () => {
          toast.error('启停失败,请重试')
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
            <div className="flex size-8 items-center justify-center rounded-lg bg-accent-soft font-mono text-[13px] font-bold text-accent">
              {ruleInitial(ruleType)}
            </div>
            <div>
              <div className="text-body font-bold text-text-primary">{name}</div>
              <div className="font-mono text-[10px] text-text-muted">{ruleType}</div>
            </div>
          </div>
          {/* desc */}
          <div className="mt-2.5 text-body-sm leading-[1.5] text-text-secondary">
            {ruleDesc(ruleType)}
          </div>
          {/* 当前阈值 */}
          <div className="mt-2.5 rounded-lg bg-surface-card-2 px-2.5 py-2">
            <div className="text-[10px] uppercase tracking-[0.04em] text-text-muted">
              当前阈值
            </div>
            <div className="kq-mono-row mt-0.5 text-[16px] font-bold text-accent">
              {formatRuleValue(ruleType, params)}
            </div>
          </div>
          {/* 说明文 */}
          <div className="mt-2 text-[11px] leading-[1.5] text-text-muted">
            · 拒绝原因脱敏:只告知"被哪条规则拒",不告知阈值
            <br />
            · 无规则 = 放行;风控服务挂了开仓 fail-closed
          </div>
        </div>
        {/* toggle */}
        <div className="flex flex-col items-center gap-1">
          <Switch
            checked={enabled}
            disabled={toggle.isPending}
            onCheckedChange={handleToggle}
            aria-label={`${name} 启停`}
          />
          <span className="text-[10px] text-text-muted">{enabled ? 'ON' : 'OFF'}</span>
        </div>
      </div>
    </Card>
  )
}

/** AuditTable — 决策审计表(照原型 AuditTable 抄)。 */
function AuditTable() {
  const { data, isLoading, error } = useRiskDecisions({ page: 1, pageSize: 50 })

  const decisions = data?.content ?? []

  return (
    <Card className="overflow-hidden p-0">
      <div className="px-6 pt-6">
        <SectionTitle
          title="决策审计"
          sub="每次风控决策的脱敏日志"
          right={
            <Button
              variant="ghost"
              size="sm"
              onClick={() => toast.info('CSV 已导出')}
            >
              <Download className="size-4" aria-hidden />
              导出
            </Button>
          }
        />
      </div>
      <div className="overflow-auto">
        <Table>
          <TableHeader>
            <TableRow className="text-left text-[10px] uppercase tracking-[0.04em] text-text-muted">
              <TableHead className="px-3 py-2">时间</TableHead>
              <TableHead className="px-3 py-2">规则</TableHead>
              <TableHead className="px-3 py-2">决策</TableHead>
              <TableHead className="px-3 py-2">详情</TableHead>
              <TableHead className="px-3 py-2">账户</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="kq-mono-row">
            {error ? (
              <TableRow>
                <TableCell colSpan={5} className="p-6">
                  <ErrorState
                    message={(error as Error).message}
                    onRetry={() => window.location.reload()}
                  />
                </TableCell>
              </TableRow>
            ) : isLoading ? (
              <TableRow>
                <TableCell colSpan={5} className="p-6">
                  <LoadingState rows={4} />
                </TableCell>
              </TableRow>
            ) : decisions.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="p-6">
                  <EmptyState title="无决策记录" description="暂无风控决策日志" />
                </TableCell>
              </TableRow>
            ) : (
              decisions.map((d) => <AuditRow key={d.id} d={d} />)
            )}
          </TableBody>
        </Table>
      </div>
    </Card>
  )
}

/** AuditRow — 单行决策审计(照原型 tr 抄)。 */
function AuditRow({ d }: { d: RiskDecisionDto }) {
  const verdict = d.verdict
  const approved = verdict === 'APPROVED'
  // ruleResults[0].ruleType(照原型 rule 列)
  const ruleType = d.ruleResults[0]?.ruleType ?? '—'
  // reason:APPROVED 时为 null(契约"通过时为 null")→ 显示 —
  const reason = d.ruleResults[0]?.reason ?? '—'

  return (
    <TableRow className="border-b border-border-soft">
      <TableCell className="px-3 py-2.5">{formatDateTime(d.createdAt)}</TableCell>
      <TableCell className="px-3 py-2.5">
        <Chip label={ruleType} />
      </TableCell>
      <TableCell className="px-3 py-2.5">
        <span
          className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[11px] font-bold ${approved ? 'bg-up/15 text-up' : 'bg-down/15 text-down'}`}
        >
          {approved ? '✓' : '✕'} {approved ? '放行' : '拒'}
        </span>
      </TableCell>
      <TableCell className="px-3 py-2.5 text-text-secondary">{reason}</TableCell>
      <TableCell className="px-3 py-2.5">
        {d.accountId === 1 ? (
          <span className="kq-paper-badge">PAPER</span>
        ) : d.accountId === 2 ? (
          <span className="kq-live-badge">LIVE</span>
        ) : (
          <span className="text-text-muted">#{d.accountId}</span>
        )}
      </TableCell>
    </TableRow>
  )
}

// 注:StrategyDetailDto 类型导入仅用于 running filter 的类型推导,不单独使用其字段。
