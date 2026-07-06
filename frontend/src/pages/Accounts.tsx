import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useExchangeAccounts } from '@/hooks/useExchangeAccounts'
import { useCreateExchangeAccount } from '@/hooks/useCreateExchangeAccount'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'

const accountSchema = z.object({
  label: z
    .string()
    .min(1, '请输入标签')
    .max(100, '标签最多 100 字符')
    .regex(/^[A-Za-z0-9 _-]+$/, '仅字母/数字/空格/_/-'),
})
type AccountInput = z.infer<typeof accountSchema>

/**
 * Accounts — 账户管理页(/portfolio,验收修复)。
 *
 * 列出当前用户交易所账户 + "添加模拟盘账户"表单。
 * PAPER 模拟盘:apiKey/apiSecret 填占位值(后端加密存储,PAPER 不走真实交易所)。
 * StrategyNew 前置校验依赖至少一个账户存在。
 */
export function Accounts() {
  const { data: accounts, isLoading, error } = useExchangeAccounts()
  const create = useCreateExchangeAccount()
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AccountInput>({
    resolver: zodResolver(accountSchema),
    defaultValues: { label: '' },
  })

  const onSubmit = (data: AccountInput) => {
    create.mutate(
      {
        exchange: 'PAPER',
        label: data.label,
        apiKey: 'paper-key',
        apiSecret: 'paper-secret',
      },
      { onSuccess: () => reset() },
    )
  }

  return (
    <div className="mx-auto max-w-[1240px] px-xl py-2xl text-text-primary">
      <header>
        <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Portfolio</p>
        <h1 className="mt-sm font-display text-h1">账户</h1>
      </header>

      <section className="mt-xl rounded-xl bg-surface-card p-2xl shadow-card">
        <h2 className="font-display text-h3">添加模拟盘账户</h2>
        <p className="mt-sm font-body text-body-sm text-text-secondary">
          模拟盘(PAPER)用占位凭证,不走真实交易所,适合策略开发与回测。至少添加一个账户才能新建策略。
        </p>
        <form onSubmit={handleSubmit(onSubmit)} className="mt-lg flex items-end gap-md">
          <div className="flex-1 space-y-sm">
            <label htmlFor="label" className="font-body text-body-sm text-text-secondary">
              账户标签
            </label>
            <Input id="label" placeholder="如 模拟盘1" {...register('label')} />
            {errors.label && (
              <p className="font-body text-caption text-down">{errors.label.message}</p>
            )}
          </div>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? '添加中…' : '添加模拟盘账户'}
          </Button>
        </form>
      </section>

      <section className="mt-lg" aria-label="账户列表">
        <h2 className="font-display text-h3">已绑定账户</h2>
        {isLoading ? (
          <LoadingState label="加载账户…" />
        ) : error ? (
          <ErrorState
            title="账户加载失败"
            message={error instanceof Error ? error.message : undefined}
          />
        ) : !accounts || accounts.length === 0 ? (
          <div className="mt-md rounded-lg border border-dashed border-border bg-surface-card p-xl text-center">
            <p className="font-body text-body-sm text-text-secondary">
              暂无账户,添加一个模拟盘账户开始策略开发
            </p>
          </div>
        ) : (
          <ul className="mt-md space-y-md">
            {accounts.map((a) => (
              <li
                key={a.id}
                className="flex items-center justify-between rounded-lg border border-border bg-surface-card p-lg"
              >
                <div>
                  <p className="font-body text-body-base">
                    {a.label} <span className="text-text-muted">· {a.exchange}</span>
                  </p>
                  <p className="mt-xs font-mono text-caption text-text-muted">
                    {a.apiKey} · {a.paperTrading ? '模拟盘' : '实盘'} · {a.status}
                  </p>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
