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
  // backtests — 列表
  // ============================================================
  globalOpts(
    program
      .command('backtests')
      .description('回测任务列表')
      .option('-s, --strategy-id <id>', '按策略 ID 过滤(不传则返回当前用户全部回测)'),
  ).action(async (opts: { strategyId?: string; format?: string; baseUrl?: string }) => {
    try {
      const creds = resolveCreds(opts)
      const qs = opts.strategyId ? `?strategyId=${opts.strategyId}` : ''
      const data = await apiGet<BacktestTaskDto[]>(creds, `/api/v1/backtests${qs}`)
      output(data, fmt(opts), (d) => {
        if (d.length === 0) return '(空)'
        return table(
          ['ID', '策略', '状态', '收益率'],
          d.map((b) => [
            String(b.id ?? '-'),
            String(b.strategyName ?? b.strategyId ?? '-'),
            String(b.status ?? '-'),
            String(b.totalReturn ?? '-'),
          ]),
        )
      })
    } catch (e) {
      fail(e)
    }
  })

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
