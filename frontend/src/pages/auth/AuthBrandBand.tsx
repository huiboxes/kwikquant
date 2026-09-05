import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { BrandMark } from '@/components/BrandMark'

/**
 * AuthBrandBand — 登录/注册页左侧品牌 band，两页共用。
 *
 * 布局：hero 与 pin masonry 走正常流两栏(hero 左、masonry 右)，
 * 窄于 1280 视口直接隐藏 masonry——结构上杜绝叠压，hero 永远独占可读宽度。
 */

type Pin =
  | { h: string; kind: 'code'; title: string; sub: string; code: string }
  | { h: string; kind: 'chart'; title: string; sub: string; curve: number[] }
  | { h: string; kind: 'pos'; title: string; sub: string; pnl: string }
  | { h: string; kind: 'quote'; title: string; sub: string }
  | { h: string; kind: 'ticker'; title: string; sub: string; price: string; chg: string }
  | { h: string; kind: 'metric'; title: string; sub: string; val: string }

const PINS: Pin[] = [
  { h: 'h-[200px]', kind: 'code', title: 'BTC Trend Rider', sub: 'v1.3.2 · 运行中', code: 'if(fast>slow){buy()}' },
  { h: 'h-[170px]', kind: 'chart', title: '回测权益曲线', sub: '+58.4% · 夏普 2.31', curve: [0, 2, 5, 3, 8, 6, 10, 12, 9, 15] },
  { h: 'h-[220px]', kind: 'pos', title: 'BTC/USDT 做多', sub: '+184.20 USDT · 模拟盘', pnl: '+184.20' },
  { h: 'h-[160px]', kind: 'quote', title: '策略开发', sub: '代码透明 · 版本可追溯' },
  { h: 'h-[200px]', kind: 'code', title: '信号执行', sub: '规则明确 · 风控前置', code: 'if(signal){buy()}' },
  { h: 'h-[150px]', kind: 'metric', title: '夏普比率', sub: '12 个月', val: '2.31' },
  { h: 'h-[190px]', kind: 'chart', title: '资金曲线', sub: '+38.1% · BTC Trend', curve: [5, 7, 6, 9, 11, 10, 13, 15, 14, 18] },
  { h: 'h-[170px]', kind: 'quote', title: '紧急停止', sub: '高风险操作 · 二次确认' },
  { h: 'h-[180px]', kind: 'ticker', title: 'ETH/USDT', sub: '实时行情', price: '3142.18', chg: '+2.34%' },
]

type ChipDot = 'onyx' | 'up' | 'warning' | 'info'
const DOT_CLASS: Record<ChipDot, string> = {
  onyx: 'bg-onyx',
  up: 'bg-up',
  warning: 'bg-warning',
  info: 'bg-info',
}
const CHIP_CLASS: Record<ChipDot, string> = {
  onyx: 'kq-chip',
  up: 'kq-chip--up',
  warning: 'kq-chip--warning',
  info: 'kq-chip--info',
}

export function AuthBrandBand() {
  return (
    <div className="relative hidden min-w-[560px] flex-[1.1] overflow-hidden border-r border-border bg-surface-canvas lg:block">
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

        {/* hero + masonry 正常流两栏 */}
        <div className="flex min-h-0 flex-1 items-stretch gap-lg py-xl">
          <div className="flex min-w-0 flex-1 flex-col justify-center">
            <h1 className="font-bold text-hero leading-[1.05] tracking-[-0.025em] text-text-primary min-[1280px]:text-display">
              写策略，做回测，<br />
              再决定是否<strong className="font-bold">实盘</strong>。
            </h1>
            <p className="mt-md max-w-[480px] text-body leading-relaxed text-text-secondary">
              加密货币量化工作台 <br /> 连接交易所，先用历史数据和模拟盘验证，再决定是否使用真实资金。
            </p>
            <div className="mt-lg flex flex-wrap gap-xs">
              <Chip dot="onyx">连接交易所</Chip>
              <Chip dot="up">模拟盘验证</Chip>
              <Chip dot="warning">代码版本管理</Chip>
              <Chip dot="info">下单前风控</Chip>
            </div>
          </div>

          {/* pin masonry：右栏正常流，右边缘渐隐；窄视口隐藏保 hero 可读宽度 */}
          <div
            data-slot="auth-masonry"
            className="kq-pin-mask hidden w-[40%] min-w-0 max-w-[360px] shrink-0 self-center overflow-hidden opacity-85 min-[1280px]:block"
          >
            <div className="columns-2 gap-sm">
              {PINS.map((p, i) => (
                <Pin key={i} p={p} />
              ))}
            </div>
          </div>
        </div>

        <div className="text-label-caps text-text-muted">
          © 2026 KwikQuant · 加密货币量化交易存在风险，请谨慎评估。
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
          <div className="text-body-sm text-text-primary">{p.code}</div>
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
          <div className="text-h2 font-semibold text-text-primary">{p.title}</div>
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
        <div className={cn('flex flex-col justify-between bg-onyx p-sm text-card', p.h)}>
          <div className="text-label-caps opacity-80">{p.title}</div>
          <div className="font-mono-num text-display font-semibold leading-none">{p.val}</div>
          <div className="text-label-caps opacity-75">{p.sub}</div>
        </div>
      )}
    </div>
  )
}

/** mini 装饰 sparkline(示意曲线，非真实数据)。权益曲线走 up 绿,与盈亏语义一致 */
function ChartLine({ curve }: { curve: number[] }) {
  const max = Math.max(...curve)
  const pts = curve.map((v, i) => `${(i / (curve.length - 1)) * 100},${60 - (v / max) * 55 - 3}`).join(' ')
  const area = `0,60 ${pts} 100,60`
  return (
    <svg viewBox="0 0 100 60" preserveAspectRatio="none" className="mt-sm flex-1">
      <polyline points={pts} fill="none" stroke="var(--up)" strokeWidth="1.5" />
      <polygon points={area} fill="var(--up)" opacity="0.15" />
    </svg>
  )
}
