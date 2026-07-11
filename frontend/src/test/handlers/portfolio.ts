import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * portfolio MSW handlers。
 * mock 数据照原型 PortfolioPage data 适配:
 *  - summary → PortfolioSummary{accounts: AccountSummary[], totalUsdt}(多账户余额聚合)
 *  - pnl → PortfolioPnl{positions: PositionPnl[], totalUnrealizedPnl}(持仓盈亏)
 *  - equity-curve → EquityPointDto[](⚠ honest:后端无此端点,mock 占位,待后端补)
 *
 * accounts 余额与 account handler BALANCES 对齐(同账户 id 同余额)。
 */
type PortfolioSummary = components['schemas']['PortfolioSummary']
type PortfolioPnl = components['schemas']['PortfolioPnl']
type EquityPointDto = components['schemas']['EquityPointDto']

const SUMMARY: PortfolioSummary = {
  accounts: [
    {
      accountId: 1,
      exchange: 'PAPER',
      label: 'PAPER 主模拟',
      balances: [{ currency: 'USDT', free: 100000, used: 0, total: 100000, usdtValue: 100000 }],
      totalUsdt: 100000,
    },
    {
      accountId: 2,
      exchange: 'BINANCE',
      label: '主账户',
      balances: [{ currency: 'USDT', free: 4800, used: 434.18, total: 5234.18, usdtValue: 5234.18 }],
      totalUsdt: 5234.18,
    },
    {
      accountId: 3,
      exchange: 'PAPER',
      label: 'PAPER-OKX 模拟',
      balances: [{ currency: 'USDT', free: 95000, used: 5000, total: 100000, usdtValue: 100000 }],
      totalUsdt: 100000,
    },
    {
      accountId: 4,
      exchange: 'OKX',
      label: 'OKX 实盘',
      balances: [{ currency: 'USDT', free: 890.5, used: 0, total: 890.5, usdtValue: 890.5 }],
      totalUsdt: 890.5,
    },
  ],
  totalUsdt: 100000 + 5234.18 + 100000 + 890.5,
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

// equity-curve mock:30 点,100000 起步上升趋势(照原型 data.equityCurve 走势)。⚠ honest:后端无此端点。
const EQUITY_CURVE: EquityPointDto[] = Array.from({ length: 30 }, (_, i) => ({
  time: `2026-07-${String(i + 1).padStart(2, '0')}T08:30:00Z`,
  equity: 100000 + Math.sin(i * 0.3) * 2000 + i * 180,
}))

export const portfolioHandlers = [
  // GET /api/v1/portfolio/summary → 多账户余额聚合(部分失败降级语义 mock 简化为全成功)
  http.get('/api/v1/portfolio/summary', () => {
    return HttpResponse.json(envelope(SUMMARY))
  }),

  // GET /api/v1/portfolio/pnl → 持仓未实现盈亏(含 positions + totalUnrealizedPnl 单点)
  http.get('/api/v1/portfolio/pnl', () => {
    return HttpResponse.json(envelope(PNL))
  }),

  // GET /api/v1/portfolio/equity-curve → 权益曲线(⚠ mock 占位,待后端补真端点)
  http.get('/api/v1/portfolio/equity-curve', () => {
    return HttpResponse.json(envelope(EQUITY_CURVE))
  }),
]
