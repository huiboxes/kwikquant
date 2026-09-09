import type { Command } from 'commander'
import { apiGet, apiPost, apiDelete } from './client.js'
import { output, table } from './output.js'
import type {
  PageDtoOrderDetailDto,
  OrderDetailDto,
  OrderSubmitResult,
  FillDto,
} from './types.js'
import {
  globalOpts,
  fmt,
  fail,
  resolveCreds,
  requireAccount,
  confirmWrite,
  verifyPositionOwnership,
  derivePositionEffect,
  sideFromPositionEffect,
} from './shared.js'

const PERP_POSITION_EFFECTS = ['OPEN_LONG', 'OPEN_SHORT', 'CLOSE_LONG', 'CLOSE_SHORT']

/** 提交类输出:状态行 + 提交时点成交快照(filledQty 非空才显;LIVE 为 null=成交异步,不承诺)。
 *  幂等 replay 场景该快照最有价值——返的是订单当前真实累计成交值。 */
function submitLine(prefix: string, r: OrderSubmitResult): string {
  const filled =
    r.filledQty != null
      ? ` filled=${r.filledQty}${r.filledAvgPrice != null ? ` @ ${r.filledAvgPrice}` : ''}`
      : ''
  return `${prefix} orderId=${r.orderId ?? '-'} status=${r.status ?? '-'}${filled}`
}

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
        const data = await apiGet<PageDtoOrderDetailDto>(creds, `/api/v1/orders?${params}`)
        output(data, fmt(opts), (d) => {
          const list = d.content ?? []
          if (list.length === 0) return '(空)'
          return table(
            ['ID', '交易对', '方向', '类型', '数量', '价格', '状态', '已成交'],
            list.map((o) => [
              String(o.orderId ?? '-'),
              String(o.symbol ?? '-'),
              String(o.side ?? '-'),
              String(o.orderType ?? '-'),
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
        const data = await apiGet<OrderDetailDto>(creds, `/api/v1/orders/${id}`)
        output(data, fmt(opts), (o) =>
          table(
            ['字段', '值'],
            Object.entries({
              orderId: o.orderId,
              symbol: o.symbol,
              marketType: o.marketType,
              side: o.side,
              // PERP 意图唯一真相源(side 是派生量:close_long 也是 sell,单看 side 分不清开空/平多)
              positionEffect: o.positionEffect,
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
          ),
        )
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
      .option(
        '--side <side>',
        '方向 buy | sell(SPOT 必填;PERP 可省略,由 --position-effect 单源派生)',
      )
      .requiredOption('--type <type>', '订单类型 market | limit')
      .requiredOption(
        '--amount <n>',
        '下单数量,单位=币数量(base coin,如 0.01 = 0.01 BTC;PERP 张数由后端按 contractSize 边界换算)',
      )
      .option('--price <p>', '限价(type=limit 必填)')
      .option('-m, --market-type <type>', '市场 spot | perp', 'spot')
      .option('--margin-mode <mode>', 'PERP 保证金模式 isolated | cross')
      .option('--leverage <n>', 'PERP 杠杆倍数(1-100,不超交易所 per-symbol 上限)')
      .option(
        '--position-effect <effect>',
        'PERP 开仓方向 open_long|open_short|close_long|close_short(省略则按 --side 派生)',
      )
      .option('--time-in-force <tif>', '有效期 GTC|IOC|FOK|GTD', 'GTC')
      .option('--stop-price <p>', '止损价(STOP 类必填)')
      .option('--expire-at <iso>', 'GTD 过期时间 ISO-8601')
      .option('--client-order-id <id>', '客户端订单标识')
      .option('--confirm', '实盘二次确认(真实成交不可逆)'),
  ).action(
    async (opts: {
      account: string
      symbol: string
      side?: string
      type: string
      amount: string
      price?: string
      marketType: string
      marginMode?: string
      leverage?: string
      positionEffect?: string
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
        const marketType = opts.marketType.toUpperCase()
        // 四象限预检全部排在 confirmWrite 之前:客户端可判的输入错误不该先弹确认再报错
        // (PAPER 免确认路径同理——错误信息先于"✓ 下单"出现)
        let side = opts.side?.toLowerCase()
        if (side !== undefined && side !== 'buy' && side !== 'sell') {
          throw new Error(`--side 非法: ${opts.side}(允许 buy/sell)`)
        }
        let effect: string | undefined
        if (marketType === 'PERP') {
          if (opts.positionEffect) {
            effect = opts.positionEffect.toUpperCase()
            if (!PERP_POSITION_EFFECTS.includes(effect)) {
              throw new Error(`--position-effect 非法: ${effect}(允许 ${PERP_POSITION_EFFECTS.join('/')})`)
            }
            // side 单源=positionEffect:省略则派生;显式给出必须与派生值一致(后端四象限校验同拒)
            const expected = sideFromPositionEffect(effect)
            if (side === undefined) {
              side = expected
              console.log(`ℹ PERP 未传 --side,按 --position-effect=${effect} 派生 side=${side}`)
            } else if (side !== expected) {
              throw new Error(
                `--side=${side} 与 --position-effect=${effect} 矛盾(${effect} 应为 --side=${expected})`,
              )
            }
          } else if (side) {
            effect = derivePositionEffect(side).toUpperCase()
            console.log(`ℹ PERP 未传 --position-effect,按 --side=${side} 派生 ${effect}`)
          } else {
            throw new Error('PERP 下单须给 --side 与 --position-effect 之一(side 可省,effect 派生开仓方向)')
          }
        } else if (!side) {
          throw new Error('SPOT 下单必填 --side buy|sell')
        }
        const creds = resolveCreds(opts)
        await confirmWrite(creds, opts.account, opts, '下单')
        const body: Record<string, unknown> = {
          accountId: Number(opts.account),
          symbol: opts.symbol,
          side: side.toUpperCase(),
          orderType: opts.type.toUpperCase(),
          amount: opts.amount,
          marketType,
          timeInForce: opts.timeInForce.toUpperCase(),
        }
        if (opts.price) body.price = opts.price
        if (opts.stopPrice) body.stopPrice = opts.stopPrice
        if (opts.expireAt) body.expireAt = opts.expireAt
        if (opts.marginMode) body.marginMode = opts.marginMode.toUpperCase()
        if (opts.leverage) body.leverage = Number(opts.leverage)
        if (effect) body.positionEffect = effect
        if (opts.clientOrderId) body.clientOrderId = opts.clientOrderId
        const data = await apiPost<OrderSubmitResult>(creds, '/api/v1/orders', body)
        output(data, fmt(opts), (r) => submitLine('✓ 订单已提交', r))
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
        const data = await apiDelete<OrderSubmitResult>(creds, `/api/v1/orders/${id}`)
        output(data, fmt(opts), (r) => `✓ 撤单已提交 orderId=${id} status=${r.status ?? '-'}`)
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
        // 归属闸:核实 positionId 属 -a 账户(防用模拟盘账户 id 免确认却平实盘持仓)
        await verifyPositionOwnership(creds, opts.account, id)
        await confirmWrite(creds, opts.account, opts, `平仓 ${id}`)
        const data = await apiPost<OrderSubmitResult>(creds, `/api/v1/positions/${id}/close`, {})
        output(data, fmt(opts), (r) => submitLine(`✓ 平仓已提交 positionId=${id}`, r))
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
        const data = await apiGet<FillDto[]>(creds, `/api/v1/orders/${orderId}/fills`)
        output(data, fmt(opts), (d) => {
          if (d.length === 0) return '(空)'
          return table(
            ['成交ID', '价格', '数量', '手续费', '方向', '流动性'],
            d.map((f) => [
              String(f.fillId ?? '-'),
              String(f.price ?? '-'),
              String(f.qty ?? '-'),
              String(f.fee ?? '-'),
              String(f.side ?? '-'),
              String(f.liquidity ?? '-'),
            ]),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )
}
