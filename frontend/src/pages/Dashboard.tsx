import { formatMoney, toDecimal } from '@/lib/money'

/**
 * Step 7 占位首屏 — 策略舰队 Dashboard。
 *
 * 用途：验证 Done AI token 全链条打通(index.css → Tailwind 工具类 → 组件)，
 * 展示 lib/money 用法(toDecimal + formatMoney 走 decimal.js)。
 *
 * 不接后端，卡片数据全是占位。业务阶段接入 React Query + strategyApi 后重写。
 * DESIGN.md 约束遵守：strategy-card 用 rounded-xl(24px) + elevation.card + hero 渐变，
 * mono 数字 tnum，涨跌用 up/down 语义色 + ↑↓ 箭头(不靠色单独表达)。
 */

interface StrategyPlaceholder {
  id: string
  name: string
  subtitle: string
  sharpe: string
  pnl24h: string // string 承载后端 BigDecimal 契约（脚手架用假数据也走 string）
}

const PLACEHOLDER_FLEET: StrategyPlaceholder[] = [
  { id: 's1', name: 'Momentum ETH', subtitle: '动量 · 4h 周期 · 3 交易所', sharpe: '2.14', pnl24h: '8.3200' },
  { id: 's2', name: 'Grid BTC', subtitle: '网格 · 现货 · 双向', sharpe: '1.87', pnl24h: '2.4100' },
  { id: 's3', name: 'Mean Reversion', subtitle: '均值回归 · USDT-M · Perp', sharpe: '1.52', pnl24h: '-1.1500' },
  { id: 's4', name: 'AI-Assisted Portfolio', subtitle: 'AI 辅助 · 6 币种 · 动态权重', sharpe: '3.02', pnl24h: '12.4400' },
]

export function Dashboard() {
  return (
    <div className="min-h-screen bg-surface-canvas text-text-primary">
      <header className="mx-auto max-w-[1240px] px-xl pt-3xl pb-2xl">
        <p className="text-label-caps text-text-muted uppercase">Fleet · 2026</p>
        <h1 className="mt-md font-display text-display">策略舰队</h1>
        <p className="mt-lg max-w-2xl font-body text-body text-text-secondary">
          脚手架占位首屏。Done AI 视觉 token 已注入 <code className="font-mono text-body-sm">src/index.css</code>
          ，此处仅为验证渲染链路 —— 业务阶段接入策略数据后此页被替换。
        </p>
      </header>
      <main className="mx-auto max-w-[1240px] px-xl pb-3xl">
        <div className="grid grid-cols-12 gap-md">
          {PLACEHOLDER_FLEET.map((s) => (
            <StrategyCard key={s.id} strategy={s} />
          ))}
        </div>
      </main>
    </div>
  )
}

function StrategyCard({ strategy }: { strategy: StrategyPlaceholder }) {
  // 金额红线：走 decimal.js，不 parseFloat/Number
  const pnlDecimal = toDecimal(strategy.pnl24h)
  const pnlText = formatMoney(pnlDecimal, { dp: 2, sign: true })
  const isUp = pnlDecimal.gte(0)

  return (
    <article className="col-span-12 md:col-span-6 lg:col-span-3">
      <div
        className="group relative overflow-hidden rounded-xl bg-surface-card shadow-card
                   transition-[transform,box-shadow] duration-[350ms]
                   hover:-translate-y-[3px] hover:shadow-card-hover"
      >
        {/* Hero 渐变条(DESIGN.md components.strategy-card-hero: Graphite → Onyx → Copper) */}
        <div
          className="h-10"
          style={{
            background:
              'linear-gradient(135deg, var(--color-primary) 0%, var(--color-onyx) 60%, var(--color-accent) 130%)',
          }}
        />
        <div className="p-lg">
          <div className="flex items-start justify-between gap-md">
            <h3 className="font-display text-h3">{strategy.name}</h3>
            <span className="font-mono-num shrink-0 rounded-full bg-surface-card-2 px-md py-1 text-caption text-text-secondary">
              Sharpe {strategy.sharpe}
            </span>
          </div>
          <p className="mt-sm text-body-sm text-text-secondary">{strategy.subtitle}</p>
          <div className="mt-lg flex items-baseline justify-between border-t border-border-soft pt-md">
            <span className="text-label-caps text-text-muted uppercase">24h PnL</span>
            <span
              className={`font-mono-num text-body font-medium ${isUp ? 'text-up' : 'text-down'}`}
              aria-label={`${isUp ? '涨' : '跌'} ${pnlText} 百分比`}
            >
              {isUp ? '↑' : '↓'} {pnlText}%
            </span>
          </div>
        </div>
      </div>
    </article>
  )
}
