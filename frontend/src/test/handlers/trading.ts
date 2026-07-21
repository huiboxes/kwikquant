import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * trading MSW handlers(orders + positions)。
 *
 * mock 数据照原型 TradingPage.jsx data.orders / data.positions 适配后端 DTO:
 *  - orders:OrderDetailDto[](side/orderType **小写**,status 6 态 NEW|PARTIAL|FILLED|CANCELLED|REJECTED|EXPIRED)
 *  - positions:PositionDto[](side LONG|SHORT|FLAT,无 uPnl/currentPrice,TD-040)
 *
 * 账户约定(同 account handler):id 1/3 = PAPER,id 2/4 = LIVE。
 * submit:201 + OrderSubmitResult(NEW);LIVE account(qty >= 10 或 amount 大)→ 200 + code=4105 风控拒(TD-041 demo)。
 */
type OrderDetailDto = components['schemas']['OrderDetailDto']
type PageDtoOrderDetailDto = components['schemas']['PageDtoOrderDetailDto']
type OrderSubmitRequest = components['schemas']['OrderSubmitRequest']
type OrderSubmitResult = components['schemas']['OrderSubmitResult']
type OrderCancelResult = components['schemas']['OrderCancelResult']
type FillDto = components['schemas']['FillDto']
type PositionDto = components['schemas']['PositionDto']

const NOW = '2026-07-04T12:00:00Z'

const ORDERS: OrderDetailDto[] = [
  // PAPER account 1 — 活动单 + 已成交
  {
    orderId: 1001,
    accountId: 1,
    symbol: 'BTC/USDT',
    marketType: 'SPOT',
    side: 'buy',
    orderType: 'limit',
    amount: 0.1,
    price: 61200,
    stopPrice: 0,
    timeInForce: 'GTC',
    expireAt: '',
    // 3.1 regen 加字段(OrderDetailDto);SPOT 给零值,PERP 阶段3.5 持仓表/订单表测试再加合约样例。
    leverage: 0,
    marginMode: '',
    positionEffect: '',
    reduceOnly: false,
    status: 'NEW',
    filledQty: 0,
    filledAvgPrice: 0,
    clientOrderId: 'client-paper-1',
    exchangeOrderId: '',
    version: 1,
    createdAt: NOW,
    updatedAt: NOW,
  },
  {
    orderId: 1002,
    accountId: 1,
    symbol: 'BTC/USDT',
    marketType: 'SPOT',
    side: 'sell',
    orderType: 'market',
    amount: 0.05,
    price: 0,
    stopPrice: 0,
    timeInForce: 'IOC',
    expireAt: '',
    // 3.1 regen 加字段(SPOT 给零值)
    leverage: 0,
    marginMode: '',
    positionEffect: '',
    reduceOnly: false,
    status: 'PARTIALLY_FILLED',
    filledQty: 0.03,
    filledAvgPrice: 61180.5,
    clientOrderId: 'client-paper-2',
    exchangeOrderId: 'ex-1002',
    version: 2,
    createdAt: NOW,
    updatedAt: NOW,
  },
  {
    orderId: 1003,
    accountId: 1,
    symbol: 'BTC/USDT',
    marketType: 'SPOT',
    side: 'buy',
    orderType: 'limit',
    amount: 0.2,
    price: 60500,
    stopPrice: 0,
    timeInForce: 'GTC',
    expireAt: '',
    // 3.1 regen 加字段(SPOT 给零值)
    leverage: 0,
    marginMode: '',
    positionEffect: '',
    reduceOnly: false,
    status: 'FILLED',
    filledQty: 0.2,
    filledAvgPrice: 60480,
    clientOrderId: 'client-paper-3',
    exchangeOrderId: 'ex-1003',
    version: 1,
    createdAt: NOW,
    updatedAt: NOW,
  },
  // LIVE account 2 — 活动单
  {
    orderId: 2001,
    accountId: 2,
    symbol: 'ETH/USDT',
    marketType: 'SPOT',
    side: 'buy',
    orderType: 'limit',
    amount: 2,
    price: 3142,
    stopPrice: 0,
    timeInForce: 'GTC',
    expireAt: '',
    // 3.1 regen 加字段(OrderDetailDto);SPOT 给零值,PERP 阶段3.5 持仓表/订单表测试再加合约样例。
    leverage: 0,
    marginMode: '',
    positionEffect: '',
    reduceOnly: false,
    status: 'NEW',
    filledQty: 0,
    filledAvgPrice: 0,
    clientOrderId: 'client-live-1',
    exchangeOrderId: '',
    version: 1,
    createdAt: NOW,
    updatedAt: NOW,
  },
  {
    orderId: 2002,
    accountId: 2,
    symbol: 'ETH/USDT',
    marketType: 'SPOT',
    side: 'sell',
    orderType: 'limit',
    amount: 1.5,
    price: 3200,
    stopPrice: 0,
    timeInForce: 'GTC',
    expireAt: '',
    // 3.1 regen 加字段(SPOT 给零值)
    leverage: 0,
    marginMode: '',
    positionEffect: '',
    reduceOnly: false,
    status: 'CANCELLED',
    filledQty: 0.5,
    filledAvgPrice: 3198,
    clientOrderId: 'client-live-2',
    exchangeOrderId: 'ex-2002',
    version: 2,
    createdAt: NOW,
    updatedAt: NOW,
  },
]

const FILLS: Record<number, FillDto[]> = {
  1002: [
    {
      fillId: 9001,
      orderId: 1002,
      accountId: 1,
      symbol: 'BTC/USDT',
      side: 'sell',
      price: 61180.5,
      qty: 0.03,
      fee: 0.012,
      feeCurrency: 'USDT',
      liquidity: 'TAKER',
      externalFillId: 'ex-fill-9001',
      filledAt: NOW,
    },
  ],
  1003: [
    {
      fillId: 9002,
      orderId: 1003,
      accountId: 1,
      symbol: 'BTC/USDT',
      side: 'buy',
      price: 60480,
      qty: 0.2,
      fee: 0.048,
      feeCurrency: 'USDT',
      liquidity: 'MAKER',
      externalFillId: 'ex-fill-9002',
      filledAt: NOW,
    },
  ],
}

const POSITIONS: PositionDto[] = [
  {
    positionId: 128,
    accountId: 1,
    symbol: 'BTC/USDT',
    side: 'LONG',
    qty: 0.42,
    avgEntryPrice: 61200,
    realizedPnl: 32.15,
    unrealizedPnl: 0,
    currentPrice: 0,
    // 3.1 regen 加字段(PositionDto);SPOT 给零值,合约持仓由后端持久化,本 mock 不演示 PERP 仓。
    leverage: 0,
    marginMode: '',
    positionSide: '',
    liquidationPrice: 0,
    maintMargin: 0,
    frozenAmount: 0,
    version: 1,
    updatedAt: NOW,
  },
  {
    positionId: 129,
    accountId: 1,
    symbol: 'SOL/USDT',
    side: 'SHORT',
    qty: 12,
    avgEntryPrice: 142.6,
    realizedPnl: -8.4,
    unrealizedPnl: 0,
    currentPrice: 0,
    // 3.1 regen 加字段(PositionDto);SPOT 给零值,合约持仓由后端持久化,本 mock 不演示 PERP 仓。
    leverage: 0,
    marginMode: '',
    positionSide: '',
    liquidationPrice: 0,
    maintMargin: 0,
    frozenAmount: 0,
    version: 1,
    updatedAt: NOW,
  },
  {
    positionId: 200,
    accountId: 2,
    symbol: 'ETH/USDT',
    side: 'LONG',
    qty: 2,
    avgEntryPrice: 3142,
    realizedPnl: 0,
    unrealizedPnl: 0,
    currentPrice: 0,
    // 3.1 regen 加字段(PositionDto);SPOT 给零值,合约持仓由后端持久化,本 mock 不演示 PERP 仓。
    leverage: 0,
    marginMode: '',
    positionSide: '',
    liquidationPrice: 0,
    maintMargin: 0,
    frozenAmount: 0,
    version: 1,
    updatedAt: NOW,
  },
]

let nextOrderId = 5000

function pageOf(list: OrderDetailDto[], page: number, pageSize: number): PageDtoOrderDetailDto {
  const start = (page - 1) * pageSize
  const slice = list.slice(start, start + pageSize)
  return {
    content: slice,
    page,
    pageSize,
    total: list.length,
    totalPages: Math.max(1, Math.ceil(list.length / pageSize)),
  }
}

export const tradingHandlers = [
  // GET /api/v1/orders?accountId=&symbol=&status=&page=&pageSize= → 分页订单
  http.get('/api/v1/orders', ({ request }) => {
    const url = new URL(request.url)
    const accountId = parseInt(url.searchParams.get('accountId') ?? '0', 10)
    const symbol = url.searchParams.get('symbol') ?? ''
    const status = url.searchParams.get('status') ?? ''
    const page = parseInt(url.searchParams.get('page') ?? '1', 10)
    const pageSize = parseInt(url.searchParams.get('pageSize') ?? '50', 10)
    let list = ORDERS.filter((o) => o.accountId === accountId)
    if (symbol) list = list.filter((o) => o.symbol === symbol)
    if (status) list = list.filter((o) => o.status === status)
    return HttpResponse.json(envelope(pageOf(list, page, pageSize)))
  }),

  // GET /api/v1/positions?accountId=&symbol= → 持仓(PositionDto[],无 uPnl,TD-040)
  http.get('/api/v1/positions', ({ request }) => {
    const url = new URL(request.url)
    const accountId = parseInt(url.searchParams.get('accountId') ?? '0', 10)
    const symbol = url.searchParams.get('symbol') ?? ''
    let list = POSITIONS.filter((p) => p.accountId === accountId)
    if (symbol) list = list.filter((p) => p.symbol === symbol)
    return HttpResponse.json(envelope(list))
  }),

  // GET /api/v1/orders/:orderId/fills → 成交明细
  http.get('/api/v1/orders/:orderId/fills', ({ params }) => {
    const orderId = parseInt(params.orderId as string, 10)
    const fills = FILLS[orderId] ?? []
    return HttpResponse.json(envelope(fills))
  }),

  // POST /api/v1/orders → 提交订单(201 NEW / 200+4105 风控拒 demo)
  http.post('/api/v1/orders', async ({ request }) => {
    const body = (await request.json()) as OrderSubmitRequest
    // 风控拒 demo:LIVE 账户(paperTrading false = id 2/4)大额订单 → 4105(TD-041)
    const isLive = body.accountId === 2 || body.accountId === 4
    const notional = body.amount * (body.price || 61200)
    if (isLive && notional > 100000) {
      return HttpResponse.json(
        envelope(null, 4105, '风控拒:超出 MAX_NOTIONAL 单笔限额(100000 USDT)'),
        { status: 200 },
      )
    }
    const result: OrderSubmitResult = {
      orderId: nextOrderId,
      status: 'NEW',
      version: 1,
      createdAt: NOW,
    }
    nextOrderId += 1
    return HttpResponse.json(envelope(result), { status: 201 })
  }),

  // DELETE /api/v1/orders/:orderId → 202 + OrderCancelResult
  http.delete('/api/v1/orders/:orderId', ({ params }) => {
    const orderId = parseInt(params.orderId as string, 10)
    const idx = ORDERS.findIndex((o) => o.orderId === orderId)
    if (idx < 0) {
      return HttpResponse.json(envelope(null, 4001, '订单不存在'), { status: 404 })
    }
    // 已成交不可撤(4101 demo)
    if (ORDERS[idx].status === 'FILLED') {
      return HttpResponse.json(
        envelope(null, 4101, '订单已成交,不可撤销'),
        { status: 422 },
      )
    }
    const result: OrderCancelResult = {
      orderId,
      status: 'CANCELLED',
      version: ORDERS[idx].version + 1,
    }
    ORDERS[idx] = { ...ORDERS[idx], status: 'CANCELLED' }
    return HttpResponse.json(envelope(result), { status: 202 })
  }),
]
