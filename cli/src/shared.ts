import type { Command } from 'commander'
import { assertAuthed, type Credentials } from './config.js'
import { apiGet, ApiError } from './client.js'
import type { Format } from './output.js'

/** 给子命令挂全局 option(--format / --base-url),支持后置 `cmd ... --format json`。 */
export function globalOpts(cmd: Command): Command {
  return cmd
    .option('--format <fmt>', '输出格式 table | json', 'table')
    .option('--base-url <url>', '后端地址(覆盖 credentials)')
}

export function fmt(opts: { format?: string }): Format {
  return opts.format === 'json' ? 'json' : 'table'
}

export function resolveCreds(opts: { baseUrl?: string }): Credentials {
  const stored = assertAuthed()
  return { ...stored, baseUrl: opts.baseUrl ?? stored.baseUrl }
}

export function fail(e: unknown): void {
  if (e instanceof ApiError) {
    console.error(`[${e.code}] ${e.message}`)
  } else if (e instanceof Error) {
    console.error(e.message)
  } else {
    console.error(String(e))
  }
  process.exitCode = 1
}

/** 行情命令通用 -e/-m option(exchange/marketType,默认 okx/spot)。 */
export function marketOpts(cmd: Command): Command {
  return cmd
    .option('-e, --exchange <exchange>', '交易所(okx | binance | bitget)', 'okx')
    .option('-m, --market-type <type>', '市场 spot | perp', 'spot')
}

export interface MarketOpts {
  exchange?: string
  marketType?: string
  format?: string
  baseUrl?: string
}

/** 规范化 exchange/marketType(大写,匹配后端枚举)。 */
export function normalizeMarket(opts: MarketOpts) {
  return {
    exchange: (opts.exchange ?? 'okx').toUpperCase(),
    marketType: (opts.marketType ?? 'spot').toUpperCase(),
  }
}

/** canonical symbol → URL path 用 - 替代 /(BTC/USDT → BTC-USDT,Spring 不解 %2F)。 */
export function symbolPath(symbol: string): string {
  return symbol.replace('/', '-')
}

/** 无 --account 时 fallback 用第一个账户 accountId(后端要求 accountId 必填)。 */
export async function requireAccount(
  creds: Credentials,
  accountId: string | undefined,
): Promise<string> {
  if (accountId) return accountId
  const accs = await apiGet<unknown[]>(creds, '/api/v1/accounts')
  const first = accs[0] as Record<string, unknown> | undefined
  if (!first?.id) {
    throw new Error('未找到账户,请 kwikquant accounts list 拿 accountId 后 --account <id>')
  }
  return String(first.id)
}

/**
 * 写操作 confirm 闸——查账户判断 PAPER/LIVE:
 * - PAPER(模拟盘)免 --confirm(成交可逆),打印提示
 * - LIVE(实盘)必须 --confirm(真实成交不可逆),否则拒
 */
export async function confirmWrite(
  creds: Credentials,
  accountId: string | undefined,
  opts: { confirm?: boolean },
  action: string,
): Promise<void> {
  if (opts.confirm) return
  if (!accountId) {
    throw new Error(`${action} 需 --account <id>(写操作)`)
  }
  const accs = await apiGet<unknown[]>(creds, '/api/v1/accounts')
  const acc = (accs as Record<string, unknown>[]).find((a) => String(a.id) === accountId)
  if (acc?.paperTrading) {
    console.log(`✓ 模拟盘 ${action}(可逆,免 --confirm)`)
    return
  }
  throw new Error(`${action} 是实盘写操作,真实成交不可逆。加 --confirm 确认执行。`)
}

/**
 * 平仓归属闸——按 -a 账户查持仓列表,核实 positionId 属该账户。
 * 修复"用模拟盘账户 id 走 paper 免确认、却平了实盘持仓"的绕过:confirmWrite 只校验
 * accountId 的 paperTrading,不校验 positionId 是否真属该账户;此处补持仓归属核实。
 */
export async function verifyPositionOwnership(
  creds: Credentials,
  accountId: string,
  positionId: string,
): Promise<void> {
  const positions = await apiGet<unknown[]>(creds, `/api/v1/positions?accountId=${accountId}`)
  const owned = (positions as Record<string, unknown>[]).some(
    (p) => String(p.positionId ?? p.id) === positionId,
  )
  if (!owned) {
    throw new Error(
      `持仓 ${positionId} 不属于账户 ${accountId}(核对 -a 账户或 position close <id> -a <真实账户>)`,
    )
  }
}

/** PERP positionEffect 自动派生:buy→OPEN_LONG / sell→OPEN_SHORT(开仓方向)。 */
export function derivePositionEffect(side: string): string {
  return side.toLowerCase() === 'sell' ? 'OPEN_SHORT' : 'OPEN_LONG'
}
