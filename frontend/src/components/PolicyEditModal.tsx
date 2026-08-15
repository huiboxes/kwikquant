import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog'
import { useAccounts } from '@/hooks/useAccounts'
import { useCreateRiskPolicy, useUpdateRiskPolicy } from '@/hooks/useRisk'
import { type RuleType, RULE_DESCRIPTION, RULE_PARAM_KEY, RULE_LABEL, RULE_DEFAULT_VALUE } from '@/lib/risk'
import { toDecimal } from '@/lib/money'
import type { components } from '@/types/api-gen'

type RiskPolicyDto = components['schemas']['RiskPolicyDto']
type RiskPolicyRequest = components['schemas']['RiskPolicyRequest']

const RULE_TYPES: RuleType[] = ['MAX_NOTIONAL', 'DAILY_LOSS_LIMIT', 'ORDER_FREQUENCY', 'MAX_INITIAL_MARGIN']

/**
 * PolicyEditModal — 风控策略编辑/新建(独立组件,RiskPage 已多 modal,抽出避免膨胀)。
 *
 * mode=create:account 下拉 + ruleType 下拉(4 种)+ name + 阈值
 * mode=edit:ruleType 显示(不可改)+ name + 阈值(account 隐藏)
 *
 * 金额走 decimal.js 校验(红线);比例 (0,1];频率 正整数。后端 validateParams 兜底。
 * 文案:中文用户语言,不暴露 PAPER/LIVE 枚举(用 模拟/实盘)。
 */
export function PolicyEditModal({
  mode,
  policy,
  policies,
  open,
  onOpenChange,
}: {
  mode: 'create' | 'edit'
  policy: RiskPolicyDto | null
  policies: RiskPolicyDto[]
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  const { data: accounts } = useAccounts()
  const createPolicy = useCreateRiskPolicy()
  const updatePolicy = useUpdateRiskPolicy()

  // useState 初始化从 props(open 时由父组件 key 重置触发 re-mount,不用 useEffect 避免 cascading render)
  const [accountId, setAccountId] = useState<number | null>(
    mode === 'create' ? (accounts?.[0]?.id ?? null) : null,
  )
  const [ruleType, setRuleType] = useState<RuleType>(
    mode === 'edit' && policy ? (policy.ruleType as RuleType) : 'MAX_NOTIONAL',
  )
  const [name, setName] = useState(mode === 'edit' && policy ? policy.name : RULE_LABEL['MAX_NOTIONAL'])
  const [threshold, setThreshold] = useState(
    mode === 'edit' && policy
      ? (policy.params?.[RULE_PARAM_KEY[policy.ruleType as RuleType]] ?? '')
      : RULE_DEFAULT_VALUE['MAX_NOTIONAL'],
  )

  const isAmount = ruleType === 'MAX_NOTIONAL' || ruleType === 'DAILY_LOSS_LIMIT'
  const isRatio = ruleType === 'MAX_INITIAL_MARGIN'

  const validate = (): string | null => {
    if (mode === 'create' && accountId == null) return '请选账户'
    if (mode === 'create' && accountId != null
      && policies.some((p) => p.accountId === accountId && p.ruleType === ruleType)) {
      return '该账户已有此规则,请编辑现有规则而非新建'
    }
    if (!name.trim()) return '请填名称'
    if (!threshold) return '请填阈值'
    const d = toDecimal(threshold)
    if (!d.isFinite()) return '阈值需为数字'
    if (isAmount) {
      if (d.lte(0)) return '金额需 > 0'
    } else if (isRatio) {
      if (d.lte(0) || d.gt(1)) return '比例需在 (0, 1],如 0.8 表示 80%'
    } else {
      // ORDER_FREQUENCY:正整数
      if (!/^\d+$/.test(threshold) || !d.gt(0)) return '频率需为正整数'
    }
    return null
  }

  const handleSubmit = () => {
    const err = validate()
    if (err) { toast.error(err); return }
    const key = RULE_PARAM_KEY[ruleType]
    const body: RiskPolicyRequest = {
      accountId: mode === 'edit' ? policy!.accountId : accountId!,
      ruleType: ruleType as RiskPolicyRequest['ruleType'],
      name: name.trim(),
      params: { [key]: threshold },
    }
    const onSuccess = () => {
      toast.success(mode === 'edit' ? '规则已更新' : '规则已创建')
      onOpenChange(false)
    }
    const onError = () => toast.error(mode === 'edit' ? '更新失败,请重试' : '创建失败,请重试')
    if (mode === 'edit') {
      updatePolicy.mutate({ policyId: policy!.id, body }, { onSuccess, onError })
    } else {
      createPolicy.mutate(body, { onSuccess, onError })
    }
  }

  const thresholdPlaceholder = isAmount ? '如 5000(USDT)' : isRatio ? '如 0.8(=80%)' : '如 60(次/min)'
  const pending = mode === 'edit' ? updatePolicy.isPending : createPolicy.isPending

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>{mode === 'edit' ? '编辑规则' : '新建规则'}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          {mode === 'create' && (
            <div className="flex flex-col gap-1.5">
              <span className="kq-label">账户</span>
              <Select value={accountId?.toString() ?? ''} onValueChange={(v) => setAccountId(parseInt(v, 10))}>
                <SelectTrigger><SelectValue placeholder="选账户" /></SelectTrigger>
                <SelectContent>
                  {(accounts ?? []).map((a) => (
                    <SelectItem key={a.id} value={a.id!.toString()}>
                      {a.label} · {a.exchange}{a.paperTrading ? '(模拟)' : '(实盘)'}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}
          <div className="flex flex-col gap-1.5">
            <span className="kq-label">规则类型</span>
            {mode === 'create' ? (
              <Select value={ruleType} onValueChange={(v) => {
                const t = v as RuleType
                setRuleType(t)
                setName(RULE_LABEL[t])
                setThreshold(RULE_DEFAULT_VALUE[t])
              }}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {RULE_TYPES.map((t) => {
                    const taken = mode === 'create' && accountId != null
                      && policies.some((p) => p.accountId === accountId && p.ruleType === t)
                    return (
                      <SelectItem key={t} value={t} disabled={taken}>
                        {RULE_LABEL[t]}{taken && '(已配)'}
                      </SelectItem>
                    )
                  })}
                </SelectContent>
              </Select>
            ) : (
              <Input value={RULE_LABEL[ruleType]} disabled />
            )}
            <p className="text-caption-sm leading-[1.5] text-text-muted">{RULE_DESCRIPTION[ruleType]}</p>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="kq-label">名称</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="如 单笔上限" />
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="kq-label">阈值</span>
            <Input value={threshold} onChange={(e) => setThreshold(e.target.value)} placeholder={thresholdPlaceholder} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>取消</Button>
          <Button size="sm" disabled={pending} onClick={handleSubmit}>
            {pending ? '保存中…' : mode === 'edit' ? '保存' : '创建'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
