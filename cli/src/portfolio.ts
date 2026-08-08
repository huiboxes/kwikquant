import type { Command } from 'commander'
import { apiGet } from './client.js'
import { output, table } from './output.js'
import { globalOpts, fmt, fail, resolveCreds, requireAccount } from './shared.js'

/** 组合 / 持仓 / 交易历史域。 */
export function registerPortfolio(program: Command): void {
  // ============================================================
  // portfolio — 组(默认 summary / pnl / equity-curve)
  // 后端参数名是 mode(非 accountMode);pnl 是实时快照(无 days 参数)
  // ============================================================
  const portfolio = program.command('portfolio').description('组合汇总(默认 summary,子命令 pnl/equity-curve)')

  // portfolio — summary(默认,参数 mode)
  globalOpts(portfolio.option('--mode <m>', '账户模式 PAPER | LIVE')).action(
    async (opts: { mode?: string; format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const qs = opts.mode ? `?mode=${opts.mode}` : ''
        const data = await apiGet<unknown>(creds, `/api/v1/portfolio/summary${qs}`)
        output(data, fmt(opts), (d) => {
          const resp = d as Record<string, unknown>
          const accounts = (resp.accounts ?? resp) as Array<Record<string, unknown>>
          if (!Array.isArray(accounts) || accounts.length === 0) return '(空)'
          return table(
            ['账户ID', '交易所', '标签', '总资产(USDT)'],
            accounts.map((a) => [
              String(a.accountId ?? a.id ?? '-'),
              String(a.exchange ?? '-'),
              String(a.label ?? '-'),
              String(a.totalUsdt ?? '-'),
            ]),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // portfolio pnl — 参数仅 mode(实时快照,无 days);PortfolioPnl = {positions, totalUnrealizedPnl}
  globalOpts(
    portfolio
      .command('pnl')
      .description('持仓未实现盈亏(实时快照)')
      .option('--mode <m>', '账户模式 PAPER | LIVE'),
  ).action(async (opts: { mode?: string; format?: string; baseUrl?: string }) => {
    try {
      const creds = resolveCreds(opts)
      const qs = opts.mode ? `?mode=${opts.mode}` : ''
      const data = await apiGet<unknown>(creds, `/api/v1/portfolio/pnl${qs}`)
      output(data, fmt(opts), (d) => {
        const v = d as Record<string, unknown>
        const positions = (v.positions ?? []) as Array<Record<string, unknown>>
        const rows: (string | number)[][] = [
          ['总未实现盈亏', String(v.totalUnrealizedPnl ?? '-')],
        ]
        if (Array.isArray(positions) && positions.length > 0) {
          positions.forEach((p) => {
            rows.push([
              `  持仓 ${String(p.symbol ?? '-')}`,
              `${String(p.unrealizedPnl ?? '-')} (账户 ${String(p.accountId ?? '-')})`,
            ])
          })
        }
        return table(['指标', '值'], rows)
      })
    } catch (e) {
      fail(e)
    }
  })

  // portfolio equity-curve — 参数 days(默认 7)+ mode;EquitySnapshot 列表
  globalOpts(
    portfolio
      .command('equity-curve')
      .description('权益曲线')
      .option('--days <n>', '查询天数', '7')
      .option('--mode <m>', '账户模式 PAPER | LIVE'),
  ).action(async (opts: { days: string; mode?: string; format?: string; baseUrl?: string }) => {
    try {
      const creds = resolveCreds(opts)
      const params = new URLSearchParams({ days: opts.days })
      if (opts.mode) params.set('mode', opts.mode)
      const data = await apiGet<unknown>(creds, `/api/v1/portfolio/equity-curve?${params}`)
      output(data, fmt(opts), (d) => {
        const list = Array.isArray(d) ? (d as Array<Record<string, unknown>>) : []
        if (list.length === 0) return '(空)'
        return table(
          ['时间', '权益'],
          list.map((p) => [
            String(p.timestamp ?? p.time ?? p.date ?? '-'),
            String(p.equity ?? p.totalUsdt ?? p.value ?? '-'),
          ]),
        )
      })
    } catch (e) {
      fail(e)
    }
  })

  // ============================================================
  // positions — 持仓列表(accountId 必填,无 --account fallback 第一个;--symbol 过滤)
  // ============================================================
  globalOpts(
    program
      .command('positions')
      .description('持仓列表')
      .option('-a, --account <id>', '账户 ID(省略则用第一个账户)')
      .option('--symbol <sym>', '按 canonical symbol 过滤'),
  ).action(
    async (opts: { account?: string; symbol?: string; format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const accountId = await requireAccount(creds, opts.account)
        const params = new URLSearchParams({ accountId })
        if (opts.symbol) params.set('symbol', opts.symbol)
        const data = await apiGet<unknown[]>(creds, `/api/v1/positions?${params}`)
        output(data, fmt(opts), (d) => {
          if (!Array.isArray(d) || d.length === 0) return '(空)'
          return table(
            ['账户', '交易对', '方向', '数量', '开仓价', '未实现盈亏', '保证金', '杠杆'],
            d.map((p) => {
              const v = p as Record<string, unknown>
              return [
                String(v.accountId ?? '-'),
                String(v.symbol ?? '-'),
                String(v.side ?? v.positionSide ?? '-'),
                String(v.qty ?? v.size ?? '-'),
                String(v.avgEntryPrice ?? v.entryPrice ?? '-'),
                String(v.unrealizedPnl ?? '-'),
                String(v.marginMode ?? '-'),
                String(v.leverage ?? '-'),
              ]
            }),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // history — 组(默认 query / stats)
  // TradeHistoryDto: orderId/accountId/symbol/side/orderType/amount/filledQty/filledAvgPrice/totalFee/totalVolume/status/createdAt
  // ============================================================
  const history = program.command('history').description('交易历史(默认 query,子命令 stats)')

  globalOpts(
    history
      .option('-a, --account <id>', '账户 ID(为空查全部账户)')
      .option('--symbol <sym>', '按 canonical symbol 过滤')
      .option('--start <iso>', '起始时间 ISO-8601')
      .option('--end <iso>', '结束时间 ISO-8601')
      .option('--page <n>', '页码', '1')
      .option('--page-size <n>', '每页条数', '20'),
  ).action(
    async (opts: {
      account?: string
      symbol?: string
      start?: string
      end?: string
      page: string
      pageSize: string
      format?: string
      baseUrl?: string
    }) => {
      try {
        const creds = resolveCreds(opts)
        const params = new URLSearchParams({ page: opts.page, pageSize: opts.pageSize })
        if (opts.account) params.set('accountId', opts.account)
        if (opts.symbol) params.set('symbol', opts.symbol)
        if (opts.start) params.set('startTime', opts.start)
        if (opts.end) params.set('endTime', opts.end)
        const data = await apiGet<unknown>(creds, `/api/v1/trade-history?${params}`)
        output(data, fmt(opts), (d) => {
          const page = d as Record<string, unknown>
          const list = (page.content ?? page) as Array<Record<string, unknown>>
          if (!Array.isArray(list) || list.length === 0) return '(空)'
          return table(
            ['时间', '账户', '交易对', '方向', '类型', '已成交', '均价', '状态'],
            list.map((t) => [
              String(t.createdAt ?? t.updatedAt ?? '-'),
              String(t.accountId ?? '-'),
              String(t.symbol ?? '-'),
              String(t.side ?? '-'),
              String(t.orderType ?? '-'),
              String(t.filledQty ?? t.amount ?? '-'),
              String(t.filledAvgPrice ?? '-'),
              String(t.status ?? '-'),
            ]),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // history stats — TradeHistoryStatsDto: totalVolume/totalFees/realizedPnl/tradingDays/winRate
  globalOpts(
    history
      .command('stats')
      .description('交易统计')
      .option('-a, --account <id>', '账户 ID(为空全部账户)')
      .option('--since <iso>', '统计起始时间 ISO-8601')
      .option('--mode <m>', '账户模式 PAPER | LIVE'),
  ).action(
    async (opts: { account?: string; since?: string; mode?: string; format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const params = new URLSearchParams()
        if (opts.account) params.set('accountId', opts.account)
        if (opts.since) params.set('since', opts.since)
        if (opts.mode) params.set('mode', opts.mode)
        const qs = params.toString() ? `?${params}` : ''
        const data = await apiGet<unknown>(creds, `/api/v1/trade-history/stats${qs}`)
        output(data, fmt(opts), (d) => {
          const v = d as Record<string, unknown>
          return table(
            ['指标', '值'],
            [
              ['成交额', String(v.totalVolume ?? '-')],
              ['累计手续费', String(v.totalFees ?? '-')],
              ['已实现盈亏', String(v.realizedPnl ?? '-')],
              ['交易天数', String(v.tradingDays ?? '-')],
              ['胜率', String(v.winRate ?? '-')],
            ],
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )
}
