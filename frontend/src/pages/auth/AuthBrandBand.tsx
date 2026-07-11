import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * AuthBrandBand — 登录/注册页左侧品牌 band(照原型 LoginPage.jsx 左半)。
 * 品牌 KQ + serif hero + 特性 chips + footer + 装饰 pin 列。
 * 登录/注册共用(产品愿景一致,文案愿景性不标通用功能)。
 */

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

export function AuthBrandBand() {
  return (
    <div className="relative min-w-[560px] flex-[1.1] overflow-hidden border-r border-border bg-accent-soft/20">
      <div className="relative flex h-screen flex-col p-xl">
        <div className="flex items-center gap-xs">
          <div className="flex h-[32px] w-[32px] items-center justify-center rounded-lg bg-accent font-mono text-body text-on-accent">
            KQ
          </div>
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
