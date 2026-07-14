import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { BrandMark } from '@/components/BrandMark'

/**
 * AuthBrandBand — 登录/注册页左侧品牌
 * 登录/注册共用
 */

type Pin =
  | { h: string; kind: 'code'; title: string; sub: string; code: string }
  | { h: string; kind: 'chart'; title: string; sub: string; curve: number[] }
  | { h: string; kind: 'pos'; title: string; sub: string; pnl: string }
  | { h: string; kind: 'quote'; title: string; sub: string }
  | { h: string; kind: 'ticker'; title: string; sub: string; price: string; chg: string }
  | { h: string; kind: 'metric'; title: string; sub: string; val: string }

// 9 pin,6 种(照原型 PINS)
const PINS: Pin[] = [
  { h: 'h-[200px]', kind: 'code', title: 'BTC Trend Rider', sub: 'v1.3.2 · 运行中', code: 'if(fast>slow){buy()}' },
  { h: 'h-[170px]', kind: 'chart', title: '回测权益曲线', sub: '+58.4% · 夏普 2.31', curve: [0, 2, 5, 3, 8, 6, 10, 12, 9, 15] },
  { h: 'h-[220px]', kind: 'pos', title: 'BTC/USDT LONG', sub: '+184.20 USDT · PAPER', pnl: '+184.20' },
  { h: 'h-[160px]', kind: 'quote', title: 'AI native', sub: '低门槛 · 自由开发' },
  { h: 'h-[200px]', kind: 'code', title: 'MCP Agent', sub: 'AI自动化 · 但可控', code: 'agent.draftOrder({})' },
  { h: 'h-[150px]', kind: 'metric', title: '夏普比率', sub: '12 个月', val: '2.31' },
  { h: 'h-[190px]', kind: 'chart', title: '资金曲线', sub: '+38.1% · BTC Trend', curve: [5, 7, 6, 9, 11, 10, 13, 15, 14, 18] },
  { h: 'h-[170px]', kind: 'quote', title: '紧急停止', sub: 'AI agent 高风险 · 强确认' },
  { h: 'h-[180px]', kind: 'ticker', title: 'ETH/USDT', sub: '实时行情', price: '3142.18', chg: '+2.34%' },
]

type ChipDot = 'accent' | 'up' | 'warning' | 'info'
const DOT_CLASS: Record<ChipDot, string> = {
  accent: 'bg-accent',
  up: 'bg-up',
  warning: 'bg-warning',
  info: 'bg-info',
}
const CHIP_CLASS: Record<ChipDot, string> = {
  accent: 'kq-chip--accent',
  up: 'kq-chip--up',
  warning: 'kq-chip--warning',
  info: 'kq-chip--info',
}

export function AuthBrandBand() {
  return (
    <div className="relative min-w-[560px] flex-[1.1] overflow-hidden border-r border-border bg-surface-canvas">
      {/* 双层 radial glow overlay */}
      <div className="kq-auth-glow" aria-hidden />

      <div className="relative flex h-screen flex-col p-xl">
        {/* brand */}
        <div className="flex items-center gap-xs">
          <BrandMark className="h-[32px] w-[32px]" />
          <div className="leading-tight">
            <div className="text-body font-bold text-text-primary">KwikQuant</div>
            <div className="text-label-caps text-text-muted">AI Native Quant</div>
          </div>
        </div>

        {/* hero */}
        <div className="flex flex-1 flex-col justify-center py-xl">
          <h1 className="font-display text-[60px] font-medium leading-[1.02] tracking-[-0.025em] text-text-primary">
            接上交易所,<br />
            策略<em className="font-display italic text-accent">自动</em>跑。
          </h1>
          <p className="mt-md max-w-[480px] text-body leading-relaxed text-text-secondary">
            加密货币量化工作台 <br /> 一键接入、策略托管、风控把关,从模拟到实盘全程可控。
          </p>
          <div className="mt-lg flex flex-wrap gap-xs">
            <Chip dot="accent">一键接入交易所</Chip>
            <Chip dot="up">模拟交易起步</Chip>
            <Chip dot="warning">AI 策略</Chip><br />
            <Chip dot="info">零信任</Chip>
          </div>
        </div>

        <div className="text-label-caps text-text-muted">
          © 2026 KwikQuant · 加密货币量化交易存在风险,请谨慎评估。
        </div>
      </div>

      {/* 9 pin masonry(右边缘,半透 + 渐隐 mask) */}
      <div className="kq-pin-mask absolute right-0 top-0 bottom-0 w-[46%] min-w-[300px] max-w-[420px] overflow-hidden px-md py-lg opacity-85">
        <div className="columns-2 gap-sm">
          {PINS.map((p, i) => (
            <Pin key={i} p={p} />
          ))}
        </div>
      </div>
    </div>
  )
}

function Chip({ dot, children }: { dot: ChipDot; children: ReactNode }) {
  return (
    <span className={cn('kq-chip', CHIP_CLASS[dot])}>
      <span className={cn(DOT_CLASS[dot], 'h-[7px] w-[7px] rounded-full')} />
      {children}
    </span>
  )
}

function Pin({ p }: { p: Pin }) {
  const isUp = 'pnl' in p ? p.pnl.startsWith('+') : 'chg' in p ? p.chg.startsWith('+') : false
  return (
    <div className="kq-pin">
      {p.kind === 'code' && (
        <div className={cn('flex flex-col justify-between bg-surface-card-2 p-sm font-mono', p.h)}>
          <div className="text-label-caps text-text-muted">{p.title}</div>
          <div className="text-body-sm text-accent">{p.code}</div>
          <div className="text-label-caps text-text-muted">{p.sub}</div>
        </div>
      )}
      {p.kind === 'chart' && (
        <div className={cn('flex flex-col bg-surface-card-2 p-sm', p.h)}>
          <div className="flex justify-between text-label-caps text-text-muted">
            <span>{p.title}</span>
            <span>{p.sub}</span>
          </div>
          <ChartLine curve={p.curve} />
        </div>
      )}
      {p.kind === 'pos' && (
        <div className={cn('flex flex-col justify-between p-sm', p.h, isUp ? 'bg-up/10' : 'bg-down/10')}>
          <div className="text-body-sm font-bold text-text-primary">{p.title}</div>
          <div className={cn('kq-mono-row text-h1 font-bold tracking-[-0.02em]', isUp ? 'text-up' : 'text-down')}>
            {p.pnl}
          </div>
          <div className="text-label-caps text-text-muted">{p.sub}</div>
        </div>
      )}
      {p.kind === 'quote' && (
        <div className={cn('flex flex-col justify-center bg-surface-card-2 p-sm', p.h)}>
          <div className="font-display text-h2 font-semibold text-accent">{p.title}</div>
          <div className="mt-xxs text-label-caps text-text-secondary">{p.sub}</div>
        </div>
      )}
      {p.kind === 'ticker' && (
        <div className={cn('flex flex-col justify-between bg-surface-card-2 p-sm', p.h)}>
          <div className="text-label-caps text-text-muted">{p.sub}</div>
          <div>
            <div className="text-body-sm font-semibold text-text-primary">{p.title}</div>
            <div className={cn('kq-mono-row text-h1 font-bold', isUp ? 'text-up' : 'text-down')}>{p.price}</div>
          </div>
          <div className={cn('text-body-sm font-semibold', isUp ? 'text-up' : 'text-down')}>{p.chg}</div>
        </div>
      )}
      {p.kind === 'metric' && (
        <div className={cn('flex flex-col justify-between bg-accent p-sm text-on-accent', p.h)}>
          <div className="text-label-caps opacity-80">{p.title}</div>
          <div className="font-display text-display font-medium leading-none">{p.val}</div>
          <div className="text-label-caps opacity-75">{p.sub}</div>
        </div>
      )}
      {/* pin body:brand dot + KwikQuant + ↗ */}
      <div className="kq-pin-body flex items-center justify-between">
        <div className="flex items-center gap-xxs">
          <span className="h-[18px] w-[18px] rounded-full bg-accent" />
          <span className="text-label-caps text-text-secondary">KwikQuant</span>
        </div>
        <span className="flex h-[22px] w-[22px] items-center justify-center rounded-full bg-surface-card-2 text-label-caps text-text-muted">↗</span>
      </div>
    </div>
  )
}

/** mini 装饰 sparkline(照原型 polyline + area,非真实数据图) */
function ChartLine({ curve }: { curve: number[] }) {
  const max = Math.max(...curve)
  const pts = curve.map((v, i) => `${(i / (curve.length - 1)) * 100},${60 - (v / max) * 55 - 3}`).join(' ')
  const area = `0,60 ${pts} 100,60`
  return (
    <svg viewBox="0 0 100 60" preserveAspectRatio="none" className="mt-sm flex-1">
      <polyline points={pts} fill="none" stroke="var(--accent)" strokeWidth="1.5" />
      <polygon points={area} fill="var(--accent)" opacity="0.15" />
    </svg>
  )
}
