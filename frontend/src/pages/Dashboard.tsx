import { Link } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { useStrategies } from '@/hooks/useStrategies'
import { StrategyCard } from '@/components/StrategyCard'
import { ActivityFeedPanel } from '@/components/ActivityFeedPanel'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { EmptyState } from '@/components/EmptyState'
import { Button } from '@/components/ui/button'

/**
 * Dashboard — 策略舰队首屏(spec §5 step 11)。
 *
 * useStrategies query:loading → LoadingState;error → ErrorState + retry;
 * empty → EmptyState + 新建;有数据 → StrategyCard grid + ActivityFeedPanel stub。
 */
export function Dashboard() {
  const { data: strategies, isLoading, error, refetch } = useStrategies()

  return (
    <div className="mx-auto max-w-[1240px] px-xl py-2xl">
      <header className="flex items-center justify-between">
        <div>
          <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">
            Fleet
          </p>
          <h1 className="mt-sm font-display text-h1">策略舰队</h1>
        </div>
        <Button asChild>
          <Link to="/strategies/new">
            <Plus className="size-4" aria-hidden />
            新建策略
          </Link>
        </Button>
      </header>

      <div className="mt-xl grid grid-cols-1 gap-lg lg:grid-cols-[2fr_1fr]">
        <section aria-label="策略列表">
          {isLoading && <LoadingState label="加载策略列表…" />}
          {error && (
            <ErrorState
              title="策略列表加载失败"
              message={error instanceof Error ? error.message : undefined}
              onRetry={() => refetch()}
            />
          )}
          {strategies && strategies.length === 0 && (
            <EmptyState
              illustration={<span className="text-h2">📈</span>}
              title="还没有策略"
              description="创建第一个策略,开始量化交易"
              action={
                <Button asChild>
                  <Link to="/strategies/new">
                    <Plus className="size-4" aria-hidden />
                    新建策略
                  </Link>
                </Button>
              }
            />
          )}
          {strategies && strategies.length > 0 && (
            <div className="grid grid-cols-1 gap-md md:grid-cols-2">
              {strategies.map((s) => (
                <StrategyCard key={s.id} strategy={s} />
              ))}
            </div>
          )}
        </section>

        <aside aria-label="最近活动">
          <ActivityFeedPanel />
        </aside>
      </div>
    </div>
  )
}
