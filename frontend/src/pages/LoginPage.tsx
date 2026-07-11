import type { ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import { useLogin } from '@/hooks/useLogin'
import { ApiError } from '@/lib/http'
import { loginSchema, type LoginInput } from '@/schemas/auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

// 装饰 pin(产品 UI mockup 概念展示,mock 占位)
const PINS = [
  { kind: 'code', title: 'BTC Trend Rider', sub: 'v1.3.2 · 运行中', code: 'if(fast>slow) buy()' },
  { kind: 'metric', title: '夏普比率', sub: '12 个月', val: '2.31' },
  { kind: 'quote', title: 'AI native', sub: '低门槛 · 自由开发' },
] as const

type ChipDot = 'accent' | 'up' | 'warning' | 'info'
const DOT_CLASS: Record<ChipDot, string> = {
  accent: 'bg-accent',
  up: 'bg-up',
  warning: 'bg-warning',
  info: 'bg-info',
}

/**
 * LoginPage — 照原型 LoginPage.jsx 移植。
 * 左:品牌 band(KQ + serif hero + 特性 chips + 装饰 pin 列)。右:登录表单(用户名+密码)。
 * 字段用 username(后端契约 LoginRequest={username,password},非原型 email);登录调 useLogin。
 * 文案愿景性(serif hero "写代码,让市场自己跑"),不标榜通用功能(记忆:登录页文案定位)。
 */
export function LoginPage() {
  const login = useLogin()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({ resolver: zodResolver(loginSchema) })

  const errMsg = login.error
    ? login.error instanceof ApiError
      ? login.error.isUnauthorized
        ? '用户名或密码错误'
        : login.error.message
      : '登录失败,请重试'
    : null

  return (
    <div className="flex min-h-screen bg-surface-canvas">
      {/* 左:品牌 band */}
      <div className="relative min-w-[560px] flex-[1.1] overflow-hidden border-r border-border bg-accent-soft/20">
        <div className="relative flex h-screen flex-col p-xl">
          <div className="flex items-center gap-xs">
            <div className="flex h-[32px] w-[32px] items-center justify-center rounded-lg bg-accent font-mono text-body text-on-accent">KQ</div>
            <div className="leading-tight">
              <div className="text-body font-bold text-text-primary">KwikQuant</div>
              <div className="text-label-caps text-text-muted">AI Native Quant</div>
            </div>
          </div>

          <div className="flex flex-1 flex-col justify-center py-xl">
            <h1 className="font-display text-[60px] font-medium leading-[1.02] tracking-[-0.025em] text-text-primary">
              写代码,<br />
              让市场<em className="font-display italic text-accent">自己</em>跑。
            </h1>
            <p className="mt-md max-w-[480px] text-body leading-relaxed text-text-secondary">
              加密货币量化,从编辑器到实盘一个工作台搞定。没有积木、没有黑盒。
            </p>
            <div className="mt-lg flex flex-wrap gap-xs">
              <Chip dot="accent">AI 辅助编码</Chip>
              <Chip dot="up">PAPER 模拟撮合</Chip>
              <Chip dot="warning">ai agent 友好</Chip>
              <Chip dot="info">不靠积木</Chip>
            </div>
          </div>

          <div className="text-label-caps text-text-muted">
            © 2026 KwikQuant · 加密货币量化交易存在风险,请谨慎评估。
          </div>
        </div>

        {/* 装饰 pin 列(右边缘,半透) */}
        <div className="absolute right-0 top-0 bottom-0 w-[46%] min-w-[300px] max-w-[420px] columns-2 gap-sm px-md py-lg opacity-60">
          {PINS.map((p, i) => (
            <Pin key={i} p={p} />
          ))}
        </div>
      </div>

      {/* 右:表单 */}
      <div className="flex min-w-[380px] flex-[0.9] items-center justify-center bg-surface-card p-xl">
        <form onSubmit={handleSubmit((input) => login.mutate(input))} className="w-full max-w-[380px]">
          <h2 className="font-display text-h1 font-medium tracking-[-0.02em] text-text-primary">继续你的策略旅程</h2>
          <p className="mt-xxs mb-lg text-body-sm text-text-muted">
            登录后从你上次停下的策略继续 — 编码、回测、模拟或实盘。
          </p>

          <label htmlFor="username" className="mb-xxs block text-label-caps text-text-muted">用户名</label>
          <Input id="username" autoComplete="username" {...register('username')} />
          {errors.username && <p className="mt-xxs text-caption text-down">{errors.username.message}</p>}

          <label htmlFor="password" className="mt-md mb-xxs block text-label-caps text-text-muted">密码</label>
          <Input id="password" type="password" autoComplete="current-password" {...register('password')} />
          {errors.password && <p className="mt-xxs text-caption text-down">{errors.password.message}</p>}

          {errMsg && <p className="mt-sm text-caption text-down" role="alert">{errMsg}</p>}

          <Button type="submit" disabled={login.isPending} className="mt-lg w-full">
            {login.isPending ? '进入中…' : '进入工作台 →'}
          </Button>

          <div className="mt-lg text-center text-body-sm text-text-muted">
            还没账户?<Link to="/register" className="text-accent hover:underline">注册</Link>
          </div>

          <div className="mt-lg rounded-md border border-dashed border-border bg-surface-card-2 p-md text-caption leading-relaxed text-text-secondary">
            <span className="font-semibold text-text-primary">演示</span> · 测试账号 demo / pass1234(msw 测试用;本地 dev 需真实账号)。
          </div>
        </form>
      </div>
    </div>
  )
}

function Chip({ dot, children }: { dot: ChipDot; children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-xxs rounded-pill border border-border bg-surface-card-2 px-sm py-xxs text-label-caps text-text-secondary">
      <span className={cn(DOT_CLASS[dot], 'h-[6px] w-[6px] rounded-full')} />
      {children}
    </span>
  )
}

function Pin({ p }: { p: (typeof PINS)[number] }) {
  if (p.kind === 'code') {
    return (
      <div className="mb-sm break-inside-avoid rounded-md bg-surface-card-2 p-sm">
        <div className="text-label-caps text-text-muted">{p.title}</div>
        <div className="mt-xs font-mono text-body-sm text-accent">{p.code}</div>
        <div className="mt-xxs text-label-caps text-text-muted">{p.sub}</div>
      </div>
    )
  }
  if (p.kind === 'metric') {
    return (
      <div className="mb-sm break-inside-avoid rounded-md bg-accent p-sm text-on-accent">
        <div className="text-label-caps opacity-80">{p.title}</div>
        <div className="font-display text-display font-medium leading-none">{p.val}</div>
        <div className="text-label-caps opacity-75">{p.sub}</div>
      </div>
    )
  }
  return (
    <div className="mb-sm break-inside-avoid rounded-md bg-surface-card-2 p-sm">
      <div className="font-display text-h2 font-semibold text-accent">{p.title}</div>
      <div className="mt-xxs text-label-caps text-text-secondary">{p.sub}</div>
    </div>
  )
}
