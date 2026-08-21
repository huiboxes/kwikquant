import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { Store } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { useTemplates, useForkTemplate } from '@/hooks/useTemplates'
import { TemplateCard } from '@/pages/templates/TemplateCard'
import { TemplateDetailDialog } from '@/pages/templates/TemplateDetailDialog'

/**
 * TemplatesPage — 策略模板库(官方模板 fork + 自动首回测)。
 *
 * 数据源:GET /api/v1/strategies/templates(官方目录，随版本发布，不会为空)。
 * fork:POST /templates/{key}/fork → 新策略(DRAFT + 已发布代码)+ best-effort 首回测；
 * 成功跳 /strategy?strategyId= 深链选中，首回测进度在策略工作台/回测页可见。
 */
export function TemplatesPage() {
  const navigate = useNavigate()
  const { data: templates, isLoading, error, refetch } = useTemplates()
  const forkMut = useForkTemplate()
  const [activeTag, setActiveTag] = useState<string | null>(null)
  const [detailKey, setDetailKey] = useState<string | null>(null)

  // 标签过滤(去重保序):null = 全部
  const allTags = useMemo(() => {
    const seen = new Set<string>()
    for (const t of templates ?? []) for (const tag of t.tags) seen.add(tag)
    return [...seen]
  }, [templates])
  const visible = useMemo(
    () => (templates ?? []).filter((t) => activeTag == null || t.tags.includes(activeTag)),
    [templates, activeTag],
  )

  /** fork 编排：成功跳策略工作台深链；首回测提交情况按后端返回提示。 */
  const handleFork = (key: string) => {
    forkMut.mutate(key, {
      onSuccess: (result) => {
        const strategy = result.strategy
        if (result.firstBacktestTaskId != null) {
          toast.success(`已复制「${strategy.name}」为我的策略`, {
            // 跳转目标是策略工作台，首回测进度在工作台「回测」tab 可见，不往 /backtest 绕
            description: '首次回测已自动提交，工作台「回测」标签可查看进度',
          })
        } else {
          toast.success(`已复制「${strategy.name}」为我的策略`, {
            description: result.backtestSkipReason ?? '可在策略工作台手动提交首次回测',
          })
        }
        setDetailKey(null)
        // 首回测已自动提交 → 带 &tab=backtest 落地即显进度(StrategyPage ?tab= 深链消费);
        // 降级(未提交)不带,落默认 session tab
        navigate(
          `/strategy?strategyId=${strategy.id}${result.firstBacktestTaskId != null ? '&tab=backtest' : ''}`,
        )
      },
      // 透出后端原因(模板不存在/策略数上限等)，不只说"请重试"
      onError: (e) => toast.error('复制失败', { description: (e as Error).message }),
    })
  }

  if (error) {
    return <ErrorState message="暂时无法加载模板库，请稍后重试" onRetry={() => refetch()} />
  }

  return (
    <div className="flex flex-col gap-[18px]">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3.5">
        <div>
          <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">策略模板库</h1>
          <p className="mt-1.5 text-body-sm text-text-secondary">
            官方策略模板 · 一键复制为我的策略 · 自动跑首次回测
          </p>
        </div>
      </div>

      {/* 标签过滤 */}
      {allTags.length > 0 && (
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="按风格筛选模板">
          <FilterChip label="全部" active={activeTag == null} onClick={() => setActiveTag(null)} />
          {allTags.map((tag) => (
            <FilterChip
              key={tag}
              label={tag}
              active={activeTag === tag}
              onClick={() => setActiveTag(activeTag === tag ? null : tag)}
            />
          ))}
        </div>
      )}

      {/* 模板网格(DESIGN.md feature card grid:3→2→1 响应式) */}
      {isLoading ? (
        <LoadingState rows={6} />
      ) : visible.length === 0 ? (
        // 区分"过滤后为空"(给换标签出路)与"目录真为空"(防御态，官方目录随版本发布理论不空)
        activeTag != null ? (
          <EmptyState
            illustration={<Store className="size-10" aria-hidden />}
            title="该分类下暂无模板"
            description="换个标签看看，或查看全部模板。"
            action={
              <Button size="sm" variant="outline" onClick={() => setActiveTag(null)}>
                查看全部
              </Button>
            }
          />
        ) : (
          <EmptyState
            illustration={<Store className="size-10" aria-hidden />}
            title="模板库为空"
            description="官方模板正在筹备中。"
          />
        )
      ) : (
        <div className="grid grid-cols-3 gap-3.5 max-[1100px]:grid-cols-2 max-[720px]:grid-cols-1">
          {visible.map((t) => (
            <TemplateCard
              key={t.key}
              template={t}
              forking={forkMut.isPending && forkMut.variables === t.key}
              // 全局锁:fork 期间禁用所有卡的 fork 按钮，防跨卡并发 fork 出重复策略
              forkDisabled={forkMut.isPending}
              onFork={handleFork}
              onDetail={setDetailKey}
            />
          ))}
        </div>
      )}

      {/* 详情 dialog(key 重挂载：每次打开重新拉详情) */}
      <TemplateDetailDialog
        key={detailKey ?? 'closed'}
        templateKey={detailKey}
        onOpenChange={(open) => { if (!open) setDetailKey(null) }}
        forking={forkMut.isPending && forkMut.variables === detailKey}
        forkDisabled={forkMut.isPending}
        onFork={handleFork}
      />
    </div>
  )
}

/** 过滤 chip(选中 = accent 强调；未选 = 中性底)。 */
function FilterChip({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-pill border px-sm py-xxs text-caption transition-colors',
        active
          ? 'border-accent bg-accent-soft text-text-primary'
          : 'border-border-soft bg-surface-card-2 text-text-secondary hover:bg-surface-3',
      )}
    >
      {label}
    </button>
  )
}
