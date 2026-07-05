import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate } from 'react-router-dom'
import {
  createStrategySchema,
  EXCHANGES,
  MARKET_TYPES,
  INTERVALS,
  type CreateStrategyInput,
} from '@/schemas/strategy'
import { useCreateStrategy } from '@/hooks/useCreateStrategy'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

/**
 * StrategyNew — inline 创建策略页(spec §5 step 12,零 modal)。
 *
 * react-hook-form + createStrategySchema + useCreateStrategy。
 * 成功跳工作区编码态 /strategies/:id?stage=code。
 */
export function StrategyNew() {
  const navigate = useNavigate()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateStrategyInput>({
    resolver: zodResolver(createStrategySchema),
    defaultValues: {
      name: '',
      description: '',
      symbol: 'BTC/USDT',
      exchange: 'BINANCE',
      marketType: 'SPOT',
      intervalValue: '1h',
      params: '{}',
    },
  })
  const create = useCreateStrategy()

  return (
    <div className="mx-auto max-w-[1240px] px-xl py-2xl text-text-primary">
      <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Strategy</p>
      <h1 className="mt-md font-display text-h1">新建策略</h1>

      <form
        onSubmit={handleSubmit((data) => create.mutate(data))}
        className="mt-xl max-w-2xl space-y-md rounded-xl bg-surface-card p-2xl shadow-card"
        noValidate
      >
        <div className="space-y-sm">
          <label htmlFor="name" className="font-body text-body-sm text-text-secondary">
            策略名称
          </label>
          <Input id="name" placeholder="如 BTC 网格" {...register('name')} />
          {errors.name && (
            <p className="font-body text-caption text-down">{errors.name.message}</p>
          )}
        </div>

        <div className="space-y-sm">
          <label htmlFor="description" className="font-body text-body-sm text-text-secondary">
            描述(可选)
          </label>
          <Input id="description" placeholder="策略说明" {...register('description')} />
        </div>

        <div className="grid grid-cols-2 gap-md">
          <div className="space-y-sm">
            <label htmlFor="symbol" className="font-body text-body-sm text-text-secondary">
              交易对
            </label>
            <Input id="symbol" placeholder="BTC/USDT" {...register('symbol')} />
            {errors.symbol && (
              <p className="font-body text-caption text-down">{errors.symbol.message}</p>
            )}
          </div>

          <div className="space-y-sm">
            <label htmlFor="exchange" className="font-body text-body-sm text-text-secondary">
              交易所
            </label>
            <select
              id="exchange"
              className="w-full rounded-md border border-border bg-surface-input px-md py-2 font-body text-body-sm text-text-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft"
              {...register('exchange')}
            >
              {EXCHANGES.map((e) => (
                <option key={e} value={e}>
                  {e}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-sm">
            <label htmlFor="marketType" className="font-body text-body-sm text-text-secondary">
              市场类型
            </label>
            <select
              id="marketType"
              className="w-full rounded-md border border-border bg-surface-input px-md py-2 font-body text-body-sm text-text-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft"
              {...register('marketType')}
            >
              {MARKET_TYPES.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-sm">
            <label htmlFor="intervalValue" className="font-body text-body-sm text-text-secondary">
              K 线周期
            </label>
            <select
              id="intervalValue"
              className="w-full rounded-md border border-border bg-surface-input px-md py-2 font-body text-body-sm text-text-primary focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-soft"
              {...register('intervalValue')}
            >
              {INTERVALS.map((i) => (
                <option key={i} value={i}>
                  {i}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="flex items-center justify-end gap-md pt-md">
          <Button type="button" variant="ghost" onClick={() => navigate(-1)}>
            取消
          </Button>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? '创建中…' : '创建并编辑代码'}
          </Button>
        </div>
      </form>
    </div>
  )
}
