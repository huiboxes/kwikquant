import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import Decimal from 'decimal.js'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { backtestSubmitSchema, type BacktestSubmitInput } from '@/schemas/backtest'
import { useSubmitBacktest } from '@/hooks/useSubmitBacktest'

/**
 * BacktestSubmitForm — 回测提交表单(spec §5 step 19)。
 *
 * react-hook-form + zodResolver(backtestSubmitSchema)。
 * 字段:symbol / exchange(select) / intervalValue(select) / startTime / endTime(datetime-local)
 *      / parameters.initial_capital(金额 input,setValueAs 走 Decimal)。
 *
 * 提交:datetime-local(本地无 Z)→ ISO-8601 UTC;parameters 对象由 useSubmitBacktest JSON.stringify。
 * 成功:onSubmitted(taskId) 由调用方设 URL ?stage=backtest&taskId=XX 深链。
 *
 * 金额红线:initial_capital 用 RHF setValueAs 走 Decimal(str).toNumber(),
 *   不用 Number()/parseFloat(ESLint 硬拦)。
 * DESIGN.md token:bg-surface-card / border-border / text-text-primary / rounded-xl / p-lg。
 */
const EXCHANGES = ['BINANCE', 'OKX', 'BITGET'] as const
const INTERVALS = ['1m', '5m', '15m', '30m', '1h', '4h', '1d'] as const

const selectClass =
  'h-11 w-full min-w-0 rounded-full border border-border bg-surface-input px-md py-2 text-body-sm text-text-primary shadow-xs outline-none transition-[color,box-shadow] focus-visible:border-accent-soft focus-visible:ring-[3px] focus-visible:ring-accent-soft/50'

export interface BacktestSubmitFormProps {
  strategyId: number
  /** 提交成功后回调(传 taskId + 本次 input,供调用方缓存 input 用于 FAILED 重试 re-POST) */
  onSubmitted?: (taskId: number, input: BacktestSubmitInput) => void
}

export function BacktestSubmitForm({ strategyId, onSubmitted }: BacktestSubmitFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<BacktestSubmitInput>({
    resolver: zodResolver(backtestSubmitSchema),
    defaultValues: {
      strategyId,
      symbol: 'BTC/USDT',
      exchange: 'BINANCE',
      intervalValue: '1h',
      startTime: '',
      endTime: '',
      parameters: { initial_capital: 10000 },
    },
  })
  const submit = useSubmitBacktest()

  const onSubmit = (data: BacktestSubmitInput) => {
    const payload: BacktestSubmitInput = {
      ...data,
      strategyId,
      // datetime-local value(本地无 Z)→ ISO-8601 UTC(后端要 date-time)
      startTime: data.startTime ? new Date(data.startTime).toISOString() : data.startTime,
      endTime: data.endTime ? new Date(data.endTime).toISOString() : data.endTime,
    }
    submit.mutate(payload, {
      onSuccess: (task) => onSubmitted?.(task.id, payload),
    })
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      className="w-full max-w-xl rounded-xl bg-surface-card p-lg shadow-card"
      noValidate
    >
      <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Backtest</p>
      <h2 className="mt-sm font-display text-h3">提交回测</h2>

      <div className="mt-lg grid grid-cols-2 gap-md">
        <div className="space-y-sm">
          <label htmlFor="bt-symbol" className="font-body text-body-sm text-text-secondary">
            交易对
          </label>
          <Input id="bt-symbol" placeholder="BTC/USDT" {...register('symbol')} />
          {errors.symbol && (
            <p className="font-body text-caption text-down">{errors.symbol.message}</p>
          )}
        </div>

        <div className="space-y-sm">
          <label htmlFor="bt-exchange" className="font-body text-body-sm text-text-secondary">
            交易所
          </label>
          <select id="bt-exchange" className={selectClass} {...register('exchange')}>
            {EXCHANGES.map((e) => (
              <option key={e} value={e}>
                {e}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-sm">
          <label htmlFor="bt-interval" className="font-body text-body-sm text-text-secondary">
            K 线周期
          </label>
          <select id="bt-interval" className={selectClass} {...register('intervalValue')}>
            {INTERVALS.map((i) => (
              <option key={i} value={i}>
                {i}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-sm">
          <label htmlFor="bt-capital" className="font-body text-body-sm text-text-secondary">
            起始资金 (USDT)
          </label>
          <Input
            id="bt-capital"
            inputMode="decimal"
            placeholder="10000"
            {...register('parameters.initial_capital', {
              // 金额红线:用 Decimal(str).toNumber() 转换,不用 Number()/parseFloat
              // 非法字符串(如 'abc')Decimal 抛错 → catch 返 NaN,zod 拒(不崩表单)
              setValueAs: (v: string) => {
                if (v === '' || v == null) return Number.NaN
                try {
                  const d = new Decimal(v)
                  if (d.isNaN()) return Number.NaN
                  return d.toNumber()
                } catch {
                  return Number.NaN
                }
              },
            })}
          />
          {errors.parameters?.initial_capital && (
            <p className="font-body text-caption text-down">
              {errors.parameters.initial_capital.message}
            </p>
          )}
        </div>

        <div className="space-y-sm">
          <label htmlFor="bt-start" className="font-body text-body-sm text-text-secondary">
            起始时间
          </label>
          <Input id="bt-start" type="datetime-local" {...register('startTime')} />
          {errors.startTime && (
            <p className="font-body text-caption text-down">{errors.startTime.message}</p>
          )}
        </div>

        <div className="space-y-sm">
          <label htmlFor="bt-end" className="font-body text-body-sm text-text-secondary">
            结束时间
          </label>
          <Input id="bt-end" type="datetime-local" {...register('endTime')} />
          {errors.endTime && (
            <p className="font-body text-caption text-down">{errors.endTime.message}</p>
          )}
        </div>
      </div>

      <Button type="submit" className="mt-xl w-full cursor-pointer" disabled={submit.isPending}>
        {submit.isPending ? '提交中…' : '提交回测'}
      </Button>
    </form>
  )
}
