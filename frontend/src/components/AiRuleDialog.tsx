import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter,
} from '@/components/ui/dialog'
import { useLlmKeys } from '@/hooks/useSettings'
import { useAccounts } from '@/hooks/useAccounts'
import { useParseRiskRules, useApplyRiskRules } from '@/hooks/useRisk'
import { RULE_LABEL, formatRuleValue, type RuleType } from '@/lib/risk'
import type { components } from '@/types/api-gen'

type RiskPolicyDto = components['schemas']['RiskPolicyDto']
type RiskPolicyParseView = components['schemas']['RiskPolicyParseView']

/**
 * AiRuleDialog — 一句话建规则(P1-2 自然语言风控)。
 *
 * 两步式:① 自然语言描述 → POST /ai/risk-policy/parse 解析为结构化预览(不落库);
 * ② 预览勾选 + 选账户(已有同 ruleType 规则标"覆盖")→ POST /risk/policies/apply
 * 单事务原子落库。AI 产出必须经用户显式确认才生效(与 MCP set_risk_rules 两阶段同语义)。
 *
 * BYO:无 LLM key 时显示引导卡(去设置页配置)，不发请求(SessionPanel 同款范式)。
 * Anti-scope 产品表达：只提取用户明确给出的阈值，预览脚注明示 AI 不推荐数值。
 *
 * 父组件用 key 重挂载范式(同 PolicyEditModal)，开即全新状态。
 */
export function AiRuleDialog({
  policies,
  open,
  onOpenChange,
}: {
  /** 当前用户全部风控策略(冲突检测：同账户同 ruleType 已存在 → 预览标"覆盖")。 */
  policies: RiskPolicyDto[]
  open: boolean
  onOpenChange: (o: boolean) => void
}) {
  const navigate = useNavigate()
  const { data: llmKeys, isSuccess: keysLoaded } = useLlmKeys()
  const { data: accounts } = useAccounts()
  const parse = useParseRiskRules()
  const apply = useApplyRiskRules()

  const [phase, setPhase] = useState<'input' | 'preview'>('input')
  const [text, setText] = useState('')
  const [parseError, setParseError] = useState<string | null>(null)
  const [result, setResult] = useState<RiskPolicyParseView | null>(null)
  /** 预览中被取消勾选的规则下标(默认全选)。 */
  const [excluded, setExcluded] = useState<Set<number>>(new Set())
  // 账户选择:用户显式选过的为准；未选时派生默认首个账户。派生而非 state+effect 回填——
  // 账户列表异步加载,useState 初值只在首渲染取一次(列表未到则永久 null)，派生天然跟上异步结果。
  const [accountIdOverride, setAccountIdOverride] = useState<number | null>(null)
  const accountId = accountIdOverride ?? accounts?.[0]?.id ?? null

  const activeKey = llmKeys && llmKeys.length > 0 ? llmKeys[0] : null

  const handleParse = () => {
    const t = text.trim()
    if (!t || activeKey == null || parse.isPending) return
    setParseError(null)
    parse.mutate(
      { llmKeyId: activeKey.id, text: t },
      {
        onSuccess: (view) => {
          setResult(view)
          setExcluded(new Set())
          setPhase('preview')
        },
        // ApiError.message = 后端脱敏文案(8004 "未能解析出风控规则…" / 8003 provider 错误)
        onError: (e) => setParseError((e as Error).message),
      },
    )
  }

  const selectedRules = (result?.rules ?? []).filter((_, i) => !excluded.has(i))
  const conflictOf = (ruleType: string) =>
    accountId != null ? policies.find((p) => p.accountId === accountId && p.ruleType === ruleType) : undefined

  const handleApply = () => {
    if (accountId == null || selectedRules.length === 0 || apply.isPending) return
    apply.mutate(
      {
        accountId,
        rules: selectedRules.map((r) => {
          const existing = conflictOf(r.ruleType)
          return {
            // 同账户已有同 ruleType → 带 policyId 覆盖更新(后端 update 路径)，否则新建
            ...(existing ? { policyId: existing.id } : {}),
            ruleType: r.ruleType,
            name: r.name,
            params: r.params,
          }
        }),
      },
      {
        onSuccess: (applied) => {
          toast.success(`已保存 ${applied.length} 条风控规则`)
          onOpenChange(false)
        },
        // 透后端 validator 原因(阈值超范围等，已是产品文案)，失败时用户才知道怎么改
        onError: (e) => toast.error('保存失败', { description: (e as Error).message }),
      },
    )
  }

  const toggleRule = (i: number) => {
    setExcluded((prev) => {
      const next = new Set(prev)
      if (next.has(i)) next.delete(i)
      else next.add(i)
      return next
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[520px]">
        <DialogHeader>
          <DialogTitle>一句话建规则</DialogTitle>
          <DialogDescription>
            用自然语言描述风控要求，AI 解析后由你确认保存
          </DialogDescription>
        </DialogHeader>

        {phase === 'input' ? (
          <div className="flex flex-col gap-3">
            {keysLoaded && !activeKey ? (
              /* BYO 引导(同 SessionPanel):无 key 不发解析请求，给可点击下一步 */
              <div className="flex items-center justify-between gap-2 rounded-lg border border-border-soft bg-surface-card-2 px-3 py-2.5">
                <p className="text-caption text-text-secondary">
                  AI 解析采用 BYO 模式：配置你的大模型密钥后即可使用。
                </p>
                <Button size="sm" variant="outline" onClick={() => navigate('/settings?tab=llm')}>
                  去配置
                </Button>
              </div>
            ) : (
              <>
                <Textarea
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                      e.preventDefault()
                      handleParse()
                    }
                  }}
                  placeholder="如：单笔下单不超过 5000 USDT，每天最多亏 2000，每分钟最多下 3 单"
                  className="min-h-[88px] resize-none bg-surface-card-2 text-caption"
                />
                <p className="text-caption-sm leading-[1.5] text-text-muted">
                  支持单笔限额、日亏限额、下单频率、保证金占用上限四类规则，可一次描述多条。
                </p>
                {parseError && (
                  <p className="text-caption text-down" role="alert">{parseError}</p>
                )}
              </>
            )}
            <DialogFooter>
              <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>取消</Button>
              <Button
                size="sm"
                disabled={!activeKey || !text.trim() || parse.isPending}
                onClick={handleParse}
              >
                <Sparkles className="size-3.5" aria-hidden />
                {parse.isPending ? '解析中…' : '开始解析'}
              </Button>
            </DialogFooter>
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {/* AI 复述(有则显):让用户先核对理解是否正确 */}
            {result?.summary && (
              <div className="rounded-lg border border-accent bg-accent-soft p-3 text-caption leading-[1.55] text-accent-warm">
                {result.summary}
              </div>
            )}
            {/* 规则预览勾选列表 */}
            <div className="flex flex-col gap-2">
              {(result?.rules ?? []).map((r, i) => {
                const checked = !excluded.has(i)
                const conflict = conflictOf(r.ruleType)
                return (
                  <label
                    key={`${r.ruleType}-${i}`}
                    className="flex cursor-pointer items-start gap-2.5 rounded-lg border border-border-soft bg-surface-card-2 px-3 py-2.5"
                  >
                    <Checkbox
                      checked={checked}
                      onCheckedChange={() => toggleRule(i)}
                      aria-label={`${r.name} 是否保存`}
                      className="mt-0.5"
                    />
                    <span className="min-w-0 flex-1">
                      <span className="flex items-baseline gap-1.5">
                        <span className="text-body-sm font-bold text-text-primary">{r.name}</span>
                        <span className="text-caption-xs text-text-muted">
                          {RULE_LABEL[r.ruleType as RuleType] ?? r.ruleType}
                        </span>
                      </span>
                      <span className="kq-mono-row mt-0.5 block text-kpi-sm font-bold text-accent">
                        {formatRuleValue(r.ruleType, r.params)}
                      </span>
                      {conflict && (
                        <span className="mt-0.5 block text-caption-xs text-warning">
                          该账户已有「{RULE_LABEL[r.ruleType as RuleType] ?? r.ruleType}」规则，保存将覆盖
                        </span>
                      )}
                    </span>
                  </label>
                )
              })}
            </div>
            {/* 目标账户；删光账户的极端态给引导卡而非空下拉(注册默认建模拟盘，仅删库后可达) */}
            {(accounts ?? []).length === 0 ? (
              <div className="flex items-center justify-between gap-2 rounded-lg border border-border-soft bg-surface-card-2 px-3 py-2.5">
                <p className="text-caption text-text-secondary">
                  请先添加交易账户，风控规则需要挂到账户上。
                </p>
                <Button size="sm" variant="outline" onClick={() => navigate('/settings?tab=accounts')}>
                  去添加
                </Button>
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <span className="kq-label">应用到账户</span>
                <Select
                  value={accountId?.toString() ?? ''}
                  onValueChange={(v) => setAccountIdOverride(parseInt(v, 10))}
                >
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
            <p className="text-caption-sm leading-[1.5] text-text-muted">
              只提取你明确说明的阈值，AI 不会推荐数值；保存前可取消勾选任意规则。
            </p>
            <DialogFooter>
              <Button
                variant="ghost"
                size="sm"
                disabled={apply.isPending}
                onClick={() => { setPhase('input'); setParseError(null) }}
              >
                重新描述
              </Button>
              <Button
                size="sm"
                disabled={accountId == null || selectedRules.length === 0 || apply.isPending}
                onClick={handleApply}
              >
                {apply.isPending ? '保存中…' : `确认保存（${selectedRules.length} 条）`}
              </Button>
            </DialogFooter>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
