import type { Command } from 'commander'
import { apiGet, apiPost, apiDelete } from './client.js'
import { output, table } from './output.js'
import {
  globalOpts,
  fmt,
  fail,
  resolveCreds,
  requireAccount,
  confirmWrite,
} from './shared.js'

/** 订单域:orders(列表)/ order get|submit|cancel / fills。 */
export function registerOrders(program: Command): void {
  // ============================================================
  // orders — 分页查询订单(OrderListQuery: accountId/symbol/status/startTime/endTime/page/pageSize)
  // ============================================================
  globalOpts(
    program
      .command('orders')
      .description('分页查询订单')
      .option('-a, --account <id>', '账户 ID(省略则用第一个账户)')
      .option('--symbol <sym>', '按 canonical symbol 过滤,如 BTC/USDT')
      .option('--status <s>', '状态过滤(逗号分隔,如 FILLED,NEW)')
      .option('--start <iso>', '起始时间 ISO-8601')
      .option('--end <iso>', '结束时间 ISO-8601')
      .option('--page <n>', '页码 1-based', '1')
      .option('--page-size <n>', '每页条数 1-100', '20'),
  ).action(
    async (opts: {
      account?: string
      symbol?: string
      status?: string
      start?: string
      end?: string
      page: string
      pageSize: string
      format?: string
      baseUrl?: string
    }) => {
      try {
        const creds = resolveCreds(opts)
        const accountId = await requireAccount(creds, opts.account)
        const params = new URLSearchParams({ accountId, page: opts.page, pageSize: opts.pageSize })
        if (opts.symbol) params.set('symbol', opts.symbol)
        if (opts.status) params.set('status', opts.status)
        if (opts.start) params.set('startTime', opts.start)
        if (opts.end) params.set('endTime', opts.end)
        const data = await apiGet<unknown>(creds, `/api/v1/orders?${params}`)
        output(data, fmt(opts), (d) => {
          const page = d as Record<string, unknown>
          const list = (page.content ?? page) as Array<Record<string, unknown>>
          if (!Array.isArray(list) || list.length === 0) return '(空)'
          return table(
            ['ID', '交易对', '方向', '类型', '数量', '价格', '状态', '已成交'],
            list.map((o) => [
              String(o.orderId ?? o.id ?? '-'),
              String(o.symbol ?? '-'),
              String(o.side ?? '-'),
              String(o.orderType ?? o.type ?? '-'),
              String(o.amount ?? '-'),
              String(o.price ?? '-'),
              String(o.status ?? '-'),
              String(o.filledQty ?? '-'),
            ]),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // order — 子命令组:get / submit / cancel
  // ============================================================
  const order = program.command('order').description('订单详情 / 下单 / 撤单')

  globalOpts(order.command('get <id>').description('查订单详情')).action(
    async (id: string, opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiGet<unknown>(creds, `/api/v1/orders/${id}`)
        output(data, fmt(opts), (d) => {
          const o = d as Record<string, unknown>
          return table(
            ['字段', '值'],
            Object.entries({
              orderId: o.orderId,
              symbol: o.symbol,
              side: o.side,
              orderType: o.orderType,
              amount: o.amount,
              price: o.price,
              status: o.status,
              filledQty: o.filledQty,
              filledAvgPrice: o.filledAvgPrice,
              leverage: o.leverage,
              marginMode: o.marginMode,
              createdAt: o.createdAt,
            }).map(([k, v]) => [k, String(v ?? '-')]),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // order submit — 写操作,confirm 闸(PAPER 免 / LIVE 必须 --confirm)
  // body 字段名对齐 OrderSubmitRequest record:accountId/symbol/side/orderType/amount/price/marketType/...
  // exchange 由 accountId 推导(后端无 exchange 字段);side/orderType/marketType/marginMode/timeInForce 均大写枚举
  globalOpts(
    order
      .command('submit')
      .description('提交订单(模拟盘免确认,实盘须 --confirm)')
      .requiredOption('-a, --account <id>', '账户 ID')
      .requiredOption('-s, --symbol <sym>', 'canonical symbol,如 BTC/USDT')
      .requiredOption('--side <side>', '方向 buy | sell')
      .requiredOption('--type <type>', '订单类型 market | limit')
      .requiredOption('--amount <n>', '下单数量')
      .option('--price <p>', '限价(type=limit 必填)')
      .option('-m, --market-type <type>', '市场 spot | perp', 'spot')
      .option('--margin-mode <mode>', 'PERP 保证金模式 isolated | cross')
      .option('--leverage <n>', 'PERP 杠杆倍数')
      .option('--time-in-force <tif>', '有效期 GTC|IOC|FOK|GTD', 'GTC')
      .option('--stop-price <p>', '止损价(STOP 类必填)')
      .option('--expire-at <iso>', 'GTD 过期时间 ISO-8601')
      .option('--client-order-id <id>', '客户端订单标识')
      .option('--confirm', '实盘二次确认(真实成交不可逆)'),
  ).action(
    async (opts: {
      account: string
      symbol: string
      side: string
      type: string
      amount: string
      price?: string
      marketType: string
      marginMode?: string
      leverage?: string
      timeInForce: string
      stopPrice?: string
      expireAt?: string
      clientOrderId?: string
      confirm?: boolean
      format?: string
      baseUrl?: string
    }) => {
      try {
        if (opts.type.toLowerCase() === 'limit' && !opts.price) {
          throw new Error('limit 单必填 --price')
        }
        const creds = resolveCreds(opts)
        await confirmWrite(creds, opts.account, opts, '下单')
        const body: Record<string, unknown> = {
          accountId: Number(opts.account),
          symbol: opts.symbol,
          side: opts.side.toUpperCase(),
          orderType: opts.type.toUpperCase(),
          amount: opts.amount,
          marketType: opts.marketType.toUpperCase(),
          timeInForce: opts.timeInForce.toUpperCase(),
        }
        if (opts.price) body.price = opts.price
        if (opts.stopPrice) body.stopPrice = opts.stopPrice
        if (opts.expireAt) body.expireAt = opts.expireAt
        if (opts.marginMode) body.marginMode = opts.marginMode.toUpperCase()
        if (opts.leverage) body.leverage = Number(opts.leverage)
        if (opts.clientOrderId) body.clientOrderId = opts.clientOrderId
        const data = await apiPost<unknown>(creds, '/api/v1/orders', body)
        output(data, fmt(opts), (d) => {
          const r = (d ?? {}) as Record<string, unknown>
          return `✓ 订单已提交 orderId=${r.orderId ?? r.id ?? '-'} status=${r.status ?? '-'}`
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // order cancel — 撤单(DELETE,取消未成交单,相对安全,免 confirm)
  globalOpts(order.command('cancel <id>').description('撤单')).action(
    async (id: string, opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiDelete<unknown>(creds, `/api/v1/orders/${id}`)
        output(data, fmt(opts), (d) => {
          const r = (d ?? {}) as Record<string, unknown>
          return `✓ 撤单已提交 orderId=${id} status=${r.status ?? '-'}`
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // position close <id> — 平仓(写操作,confirm 闸;后端 POST /positions/{id}/close)
  // ============================================================
  globalOpts(
    program
      .command('position')
      .description('持仓写操作')
      .command('close <id>')
      .description('平仓(模拟盘免确认,实盘须 --confirm)')
      .requiredOption('-a, --account <id>', '账户 ID(归属校验)')
      .option('--confirm', '实盘二次确认(真实成交不可逆)'),
  ).action(
    async (id: string, opts: { account: string; confirm?: boolean; format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        await confirmWrite(creds, opts.account, opts, `平仓 ${id}`)
        const data = await apiPost<unknown>(creds, `/api/v1/positions/${id}/close`, {})
        output(data, fmt(opts), (d) => {
          const r = (d ?? {}) as Record<string, unknown>
          return `✓ 平仓已提交 positionId=${id} status=${r.status ?? '-'}`
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // fills <orderId> — 成交明细
  // ============================================================
  globalOpts(program.command('fills <orderId>').description('查订单成交明细')).action(
    async (orderId: string, opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiGet<unknown[]>(creds, `/api/v1/orders/${orderId}/fills`)
        output(data, fmt(opts), (d) => {
          if (!Array.isArray(d) || d.length === 0) return '(空)'
          return table(
            ['成交ID', '价格', '数量', '手续费', '方向', '流动性'],
            d.map((f) => {
              const v = f as Record<string, unknown>
              return [
                String(v.fillId ?? v.id ?? '-'),
                String(v.price ?? '-'),
                String(v.qty ?? '-'),
                String(v.fee ?? '-'),
                String(v.side ?? '-'),
                String(v.liquidity ?? '-'),
              ]
            }),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )
}
