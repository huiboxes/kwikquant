import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * portfolio MSW handlers。支持 ?mode=PAPER|LIVE 过滤。
 *
 * 注:PortfolioSummary 契约只有 accounts[] 字段(无顶层 totalUsdt);账户级有 AccountSummary.totalUsdt,
 * 顶层总额由消费方(SidebarRail 等)reduce accounts.totalUsdt 算。
 */
type PortfolioSummary = components['schemas']['PortfolioSummary']
type PortfolioPnl = components['schemas']['PortfolioPnl']
type EquityPointDto = components['schemas']['EquityPointDto']

const SUMMARY: PortfolioSummary = {
  accounts: [
    {
      accountId: 1,
      exchange: 'BINANCE',
      label: 'BINANCE 模拟',
      balances: [{ currency: 'USDT', free: 100000, used: 0, total: 100000, usdtValue: 100000 }],
      totalUsdt: 100000,
      paperTrading: true,
    },
    {
      accountId: 2,
      exchange: 'BINANCE',
      label: '主账户',
      balances: [
        { currency: 'USDT', free: 4800, used: 434.18, total: 5234.18, usdtValue: 5234.18 },
        { currency: 'BTC', free: 0.1, used: 0, total: 0.1, usdtValue: 5000 },
      ],
      totalUsdt: 5234.18,
      paperTrading: false,
    },
    {
      accountId: 3,
      exchange: 'OKX',
      label: 'OKX 模拟',
      balances: [{ currency: 'USDT', free: 95000, used: 5000, total: 100000, usdtValue: 100000 }],
      totalUsdt: 100000,
      paperTrading: true,
    },
    {
      accountId: 4,
      exchange: 'OKX',
      label: 'OKX 实盘',
      balances: [{ currency: 'USDT', free: 890.5, used: 0, total: 890.5, usdtValue: 890.5 }],
      totalUsdt: 890.5,
      paperTrading: false,
    },
  ],
}

const PNL: PortfolioPnl = {
  positions: [
    { accountId: 1, symbol: 'BTC/USDT', side: 'LONG', qty: 0.42, avgEntryPrice: 61200, currentPrice: 62500, unrealizedPnl: 546, realizedPnl: 0 },
    { accountId: 2, symbol: 'ETH/USDT', side: 'LONG', qty: 2.0, avgEntryPrice: 3142, currentPrice: 3100, unrealizedPnl: -84, realizedPnl: 0 },
    { accountId: 1, symbol: 'SOL/USDT', side: 'SHORT', qty: 12, avgEntryPrice: 142.6, currentPrice: 138.2, unrealizedPnl: 52.8, realizedPnl: 0 },
    { accountId: 3, symbol: 'BTC/USDT', side: 'LONG', qty: 0.1, avgEntryPrice: 60800, currentPrice: 62500, unrealizedPnl: 170, realizedPnl: 0 },
  ],
  totalUnrealizedPnl: 546 - 84 + 52.8 + 170,
}

const EQUITY_CURVE: EquityPointDto[] = Array.from({ length: 30 }, (_, i) => ({
  time: `2026-07-${String(i + 1).padStart(2, '0')}T08:30:00Z`,
  equity: 100000 + Math.sin(i * 0.3) * 2000 + i * 180,
}))

// 按 mode 过滤(PAPER/LIVE/ALL)。accounts 现带 paperTrading 字段(对齐后端留账1 修复),
// mock 用 a.paperTrading 判断(不再用 accountId 集合,真实后端也是字段判断)。
function filterByMode(mode: string | null) {
  const isPaperAccount = (a: { paperTrading?: boolean }) => a.paperTrading === true
  if (mode === 'ALL') {
    // 全量(前端 Dashboard 按 a.paperTrading 拆;对齐后端 filterByMode ALL 分支)
    const allAccounts = SUMMARY.accounts ?? []
    const allPositions = PNL.positions ?? []
    const allPnl = allPositions.reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0)
    return {
      summary: { accounts: allAccounts } as PortfolioSummary,
      pnl: { positions: allPositions, totalUnrealizedPnl: allPnl } as PortfolioPnl,
    }
  }
  if (mode === 'PAPER') {
    const paperAccounts = (SUMMARY.accounts ?? []).filter(isPaperAccount)
    const paperPositions = (PNL.positions ?? []).filter((p) => p.accountId === 1 || p.accountId === 3)
    const paperPnl = paperPositions.reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0)
    return {
      summary: { accounts: paperAccounts } as PortfolioSummary,
      pnl: { positions: paperPositions, totalUnrealizedPnl: paperPnl } as PortfolioPnl,
    }
  }
  // LIVE or null → exclude paper (backward compat)
  const liveAccounts = (SUMMARY.accounts ?? []).filter((a) => !isPaperAccount(a))
  const livePositions = (PNL.positions ?? []).filter((p) => p.accountId === 2 || p.accountId === 4)
  const livePnl = livePositions.reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0)
  return {
    summary: { accounts: liveAccounts } as PortfolioSummary,
    pnl: { positions: livePositions, totalUnrealizedPnl: livePnl } as PortfolioPnl,
  }
}

export const portfolioHandlers = [
  http.get('/api/v1/portfolio/summary', ({ request }) => {
    const mode = new URL(request.url).searchParams.get('mode')
    return HttpResponse.json(envelope(filterByMode(mode).summary))
  }),

  http.get('/api/v1/portfolio/pnl', ({ request }) => {
    const mode = new URL(request.url).searchParams.get('mode')
    return HttpResponse.json(envelope(filterByMode(mode).pnl))
  }),

  http.get('/api/v1/portfolio/equity-curve', ({ request }) => {
    const mode = new URL(request.url).searchParams.get('mode')
    // Return empty curve for modes with no data; mock curve only for default
    if (mode === 'PAPER' || mode === 'LIVE') {
      return HttpResponse.json(envelope([]))
    }
    return HttpResponse.json(envelope(EQUITY_CURVE))
  }),
]
