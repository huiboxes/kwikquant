import type { Command } from 'commander'
import { apiGet, apiPost } from './client.js'
import { output, table } from './output.js'
import type { StrategyDetailDto, BacktestTaskDto } from './types.js'
import { globalOpts, fmt, fail, resolveCreds } from './shared.js'

/** 策略 / 回测域(含生命周期写操作)。 */
export function registerStrategy(program: Command): void {
  // ============================================================
  // strategies — 列表
  // ============================================================
  globalOpts(program.command('strategies').description('策略列表')).action(
    async (opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiGet<StrategyDetailDto[]>(creds, '/api/v1/strategies')
        output(data, fmt(opts), (d) => {
          if (d.length === 0) return '(空)'
          return table(
            ['ID', '名称', '交易所', '市场', '保证金', '杠杆', '状态'],
            d.map((s) => [
              String(s.id ?? '-'),
              String(s.name ?? '-'),
              String(s.exchange ?? '-'),
              String(s.marketType ?? '-'),
              String(s.marginMode ?? '-'),
              String(s.leverage ?? '-'),
              String(s.status ?? '-'),
            ]),
          )
        })
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // strategy — 组(get / start / stop / pause / restart)
  // StartRequest(Long accountId):首次启动/切换账户必传,resume(PAUSED)省略
  // ============================================================
  const strategy = program.command('strategy').description('策略详情与生命周期')

  globalOpts(strategy.command('get <id>').description('查策略详情')).action(
    async (id: string, opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiGet<StrategyDetailDto>(creds, `/api/v1/strategies/${id}`)
        output(data, fmt(opts), (v) =>
          table(
            ['字段', '值'],
            Object.entries({
              id: v.id,
              name: v.name,
              description: v.description,
              symbol: v.symbol,
              exchange: v.exchange,
              marketType: v.marketType,
              marginMode: v.marginMode,
              leverage: v.leverage,
              status: v.status,
              intervalValue: v.intervalValue,
            }).map(([k, x]) => [k, String(x ?? '-')]),
          ),
        )
      } catch (e) {
        fail(e)
      }
    },
  )

  // strategy start — 高危(可能启动实盘),须 --confirm;body {accountId}(首次必填,resume 省略)
  globalOpts(
    strategy
      .command('start <id>')
      .description('启动策略(高危,可能启动实盘交易,须 --confirm)')
      .option('-a, --account <id>', '账户 ID(首次启动/切换账户必填,resume 省略)')
      .option('--confirm', '二次确认'),
  ).action(
    async (id: string, opts: { account?: string; confirm?: boolean; format?: string; baseUrl?: string }) => {
      try {
        if (!opts.confirm) {
          throw new Error('策略启动是高危操作(可能启动实盘交易,真实成交不可逆),加 --confirm 确认执行')
        }
        const creds = resolveCreds(opts)
        const body: Record<string, unknown> = {}
        if (opts.account) body.accountId = Number(opts.account)
        const data = await apiPost<StrategyDetailDto>(creds, `/api/v1/strategies/${id}/start`, body)
        output(data, fmt(opts), (r) => `✓ 策略 ${id} 已启动 status=${r.status ?? '-'}`)
      } catch (e) {
        fail(e)
      }
    },
  )

  // strategy stop — 停止(安全,免 confirm)
  globalOpts(strategy.command('stop <id>').description('停止策略')).action(
    async (id: string, opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiPost<StrategyDetailDto>(creds, `/api/v1/strategies/${id}/stop`, {})
        output(data, fmt(opts), (r) => `✓ 策略 ${id} 已停止 status=${r.status ?? '-'}`)
      } catch (e) {
        fail(e)
      }
    },
  )

  // strategy pause — 暂停(安全,免 confirm)
  globalOpts(strategy.command('pause <id>').description('暂停策略')).action(
    async (id: string, opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiPost<StrategyDetailDto>(creds, `/api/v1/strategies/${id}/pause`, {})
        output(data, fmt(opts), (r) => `✓ 策略 ${id} 已暂停 status=${r.status ?? '-'}`)
      } catch (e) {
        fail(e)
      }
    },
  )

  // strategy restart — 高危(可能重启实盘),须 --confirm;body {accountId}(可选)
  globalOpts(
    strategy
      .command('restart <id>')
      .description('重启策略(高危,须 --confirm)')
      .option('-a, --account <id>', '账户 ID(切换账户时必填)')
      .option('--confirm', '二次确认'),
  ).action(
    async (id: string, opts: { account?: string; confirm?: boolean; format?: string; baseUrl?: string }) => {
      try {
        if (!opts.confirm) {
          throw new Error('策略重启是高危操作(可能重启实盘交易),加 --confirm 确认执行')
        }
        const creds = resolveCreds(opts)
        const body: Record<string, unknown> = {}
        if (opts.account) body.accountId = Number(opts.account)
        const data = await apiPost<StrategyDetailDto>(creds, `/api/v1/strategies/${id}/restart`, body)
        output(data, fmt(opts), (r) => `✓ 策略 ${id} 已重启 status=${r.status ?? '-'}`)
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // backtests — 列表 + submit 提交
  // ============================================================
  const backtests = globalOpts(
    program
      .command('backtests')
      .description('回测任务列表')
      .option('-s, --strategy-id <id>', '按策略 ID 过滤(不传则返回当前用户全部回测)'),
  )
  backtests.action(async (opts: { strategyId?: string; format?: string; baseUrl?: string }) => {
    try {
      const creds = resolveCreds(opts)
      const qs = opts.strategyId ? `?strategyId=${opts.strategyId}` : ''
      const data = await apiGet<BacktestTaskDto[]>(creds, `/api/v1/backtests${qs}`)
      output(data, fmt(opts), (d) => {
        if (d.length === 0) return '(空)'
        return table(
          ['ID', '策略', '市场', '状态', '收益率'],
          d.map((b) => [
            String(b.id ?? '-'),
            String(b.strategyName ?? b.strategyId ?? '-'),
            String(b.marketType ?? '-'),
            String(b.status ?? '-'),
            String(b.totalReturn ?? '-'),
          ]),
        )
      })
    } catch (e) {
      fail(e)
    }
  })

  // backtests submit — 提交回测(免确认:不产生成交,任务可弃)。
  // 单标的 --symbol / 组合 --symbols 互斥;两者都省略回退策略绑定 symbol(单标的)。
  globalOpts(
    backtests
      .command('submit <strategyId>')
      .description('提交回测任务(组合回测传 --symbols 多标的;免确认,不产生成交)')
      .option('--symbol <sym>', '单标的(与 --symbols 互斥;省略用策略绑定)')
      .option('--symbols <list>', '组合多标的,逗号分隔 2-20 个(与 --symbol 互斥)')
      .option('-e, --exchange <ex>', '交易所(省略用策略绑定)')
      .option('--interval <iv>', 'K 线周期 1m/5m/15m/1h/4h/1d(省略用策略绑定)')
      .requiredOption('--start <iso>', '起始时间 ISO-8601(如 2025-01-01T00:00:00Z)')
      .requiredOption('--end <iso>', '结束时间 ISO-8601')
      .option('--params <json>', '任务 parameters JSON 字符串(如 \'{"fast":14}\')')
      .option('--allow-funding-proxy', 'PERP 资金费缺期时允许 Binance 跨所代理补写'),
  ).action(
    async (
      strategyId: string,
      opts: {
        symbol?: string
        symbols?: string
        exchange?: string
        interval?: string
        start: string
        end: string
        params?: string
        allowFundingProxy?: boolean
        format?: string
        baseUrl?: string
      },
    ) => {
      try {
        if (opts.symbol && opts.symbols) {
          throw new Error('--symbol 与 --symbols 互斥(组合回测只传 --symbols)')
        }
        const body: Record<string, unknown> = {
          strategyId: Number(strategyId),
          startTime: opts.start,
          endTime: opts.end,
        }
        if (opts.symbols) {
          const list = opts.symbols
            .split(',')
            .map((s) => s.trim())
            .filter((s) => s.length > 0)
          // 细粒度校验(canonical 大写/去重/格式)由后端 validatePortfolioSymbols 单源裁决
          if (list.length < 2 || list.length > 20) {
            throw new Error(`--symbols 需要 2-20 个标的(当前 ${list.length})`)
          }
          body.symbols = list
        } else if (opts.symbol) {
          body.symbol = opts.symbol
        }
        if (opts.exchange) body.exchange = opts.exchange.toUpperCase()
        if (opts.interval) body.intervalValue = opts.interval
        if (opts.params) body.parameters = opts.params
        if (opts.allowFundingProxy) body.allowFundingProxy = true
        const creds = resolveCreds(opts)
        const data = await apiPost<BacktestTaskDto>(creds, '/api/v1/backtests', body)
        output(
          data,
          fmt(opts),
          (t) =>
            `✓ 已提交回测任务 #${t.id ?? '-'} status=${t.status ?? '-'}` +
            `\n  轮询进度: kwikquant backtest ${t.id ?? '<id>'}`,
        )
      } catch (e) {
        fail(e)
      }
    },
  )

  // ============================================================
  // backtest <id> — 详情
  // ============================================================
  globalOpts(program.command('backtest <id>').description('查回测任务详情')).action(
    async (id: string, opts: { format?: string; baseUrl?: string }) => {
      try {
        const creds = resolveCreds(opts)
        const data = await apiGet<BacktestTaskDto>(creds, `/api/v1/backtests/${id}`)
        output(data, fmt(opts), (v) =>
          table(
            ['字段', '值'],
            Object.entries({
              id: v.id,
              strategyId: v.strategyId,
              status: v.status,
              marketType: v.marketType,
              symbol: v.symbol,
              exchange: v.exchange,
              interval: v.intervalValue,
              startTime: v.startTime,
              endTime: v.endTime,
              totalReturn: v.totalReturn,
            }).map(([k, x]) => [k, String(x ?? '-')]),
          ),
        )
      } catch (e) {
        fail(e)
      }
    },
  )
}
