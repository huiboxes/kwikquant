#!/usr/bin/env node
import { Command } from 'commander'
import {
  loadCredentials,
  saveCredentials,
  clearCredentials,
  assertAuthed,
  checkPermissions,
  type Credentials,
} from './config.js'
import { apiGet, ApiError } from './client.js'
import { output, table, type Format } from './output.js'

/** 给子命令挂全局 option(--format / --base-url),支持后置 `cmd ... --format json`。 */
function globalOpts(cmd: Command): Command {
  return cmd
    .option('--format <fmt>', '输出格式 table | json', 'table')
    .option('--base-url <url>', '后端地址(覆盖 credentials)')
}

function fmt(opts: { format?: string }): Format {
  return opts.format === 'json' ? 'json' : 'table'
}

function resolveCreds(opts: { baseUrl?: string }): Credentials {
  const stored = assertAuthed()
  return { ...stored, baseUrl: opts.baseUrl ?? stored.baseUrl }
}

function fail(e: unknown): void {
  if (e instanceof ApiError) {
    console.error(`[${e.code}] ${e.message}`)
  } else if (e instanceof Error) {
    console.error(e.message)
  } else {
    console.error(String(e))
  }
  process.exitCode = 1
}

const program = new Command()
program
  .name('kwikquant')
  .description('KwikQuant 命令行工具——直连加密量化后端(行情 / 账户 / 组合 / 持仓)')
  .version('0.1.0')

// ============================================================
// auth —— CLI 直连 REST(/api/v1/**),走 JWT 鉴权;PAT 仅 MCP client 用,CLI 不签不发
// ============================================================
const auth = program.command('auth').description('登录与凭证管理')

auth
  .command('login')
  .argument('<username>', '用户名')
  .argument('<password>', '密码')
  .option('--base-url <url>', '后端地址', 'http://localhost:8080')
  .action(async (username: string, password: string, opts: { baseUrl: string }) => {
    try {
      const loginRes = await fetch(`${opts.baseUrl}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })
      const loginJson = (await loginRes.json()) as {
        code: number
        message: string
        data: { accessToken: string; expiresIn?: number } | null
      }
      if (!loginRes.ok || !loginJson.data?.accessToken) {
        throw new Error(`登录失败: ${loginJson.message ?? `HTTP ${loginRes.status}`}`)
      }
      const jwt = loginJson.data.accessToken
      const expiresIn = loginJson.data.expiresIn
      const expiresAt = expiresIn
        ? new Date(Date.now() + expiresIn * 1000).toISOString()
        : undefined
      saveCredentials({ baseUrl: opts.baseUrl, jwt, username, expiresAt })
      console.log(`✓ 已登录 ${username},JWT 已存 ~/.kwikquant/credentials.json`)
      if (expiresAt) console.log(`  过期:${expiresAt}`)
      console.log('  验证:kwikquant accounts list | quote BTC/USDT | portfolio')
    } catch (e) {
      fail(e)
    }
  })

auth.command('status').action(() => {
  checkPermissions()
  const creds = loadCredentials()
  if (!creds?.jwt) {
    console.log('未登录。请 kwikquant auth login <username> <password>')
    return
  }
  console.log(`已登录:${creds.username ?? '(unknown)'}`)
  console.log(`后端:${creds.baseUrl}`)
  console.log(`JWT 过期:${creds.expiresAt ?? '(unknown)'}`)
  console.log('凭证文件:~/.kwikquant/credentials.json (0600)')
})

auth.command('logout').action(() => {
  clearCredentials()
  console.log('✓ 已清除本地凭证')
})

// ============================================================
// accounts
// ============================================================
const accounts = program.command('accounts').description('交易所账户')

globalOpts(accounts.command('list')).action(async (opts: { format?: string; baseUrl?: string }) => {
  try {
    const creds = resolveCreds(opts)
    const data = await apiGet<unknown[]>(creds, '/api/v1/accounts')
    output(data, fmt(opts), (d) => {
      const rows = d.map((a) => {
        const v = a as Record<string, unknown>
        return [
          String(v.id ?? '-'),
          String(v.exchange ?? '-'),
          String(v.label ?? '-'),
          v.paperTrading ? '模拟盘' : '实盘',
          String(v.status ?? '-'),
        ]
      })
      return table(['ID', '交易所', '标签', '类型', '状态'], rows)
    })
  } catch (e) {
    fail(e)
  }
})

globalOpts(accounts.command('balance <accountId>')).action(
  // BalanceSnapshot = { currencies: Map<币种, {free, used, total}> }
  async (accountId: string, opts: { format?: string; baseUrl?: string }) => {
    try {
      const creds = resolveCreds(opts)
      const data = await apiGet<unknown>(creds, `/api/v1/accounts/${accountId}/balance`)
      output(data, fmt(opts), (d) => {
        const resp = d as Record<string, unknown>
        const currencies = (resp.currencies ?? resp) as Record<string, unknown>
        const entries = Object.entries(currencies).filter(([, bal]) => bal != null)
        if (entries.length === 0) return '(空)'
        const rows = entries.map(([ccy, bal]) => {
          const b = (bal ?? {}) as Record<string, unknown>
          return [ccy, String(b.free ?? '-'), String(b.used ?? '-'), String(b.total ?? '-')]
        })
        return table(['币种', '可用', '冻结', '总额'], rows)
      })
    } catch (e) {
      fail(e)
    }
  },
)

// ============================================================
// quote —— path 枚举大写(OKX/SPOT),symbol 用 "-" 替代 "/"(BTC-USDT);响应嵌套 {ticker, stale}
// ============================================================
globalOpts(
  program
    .command('quote <symbol>')
    .description('查最新价(默认 okx spot,例:BTC/USDT)')
    .option('-e, --exchange <exchange>', '交易所', 'okx')
    .option('-m, --market-type <type>', '市场 spot | perp', 'spot'),
).action(
  async (
    symbol: string,
    opts: { exchange: string; marketType: string; format?: string; baseUrl?: string },
  ) => {
    try {
      const creds = resolveCreds(opts)
      const exchange = opts.exchange.toUpperCase()
      const marketType = opts.marketType.toUpperCase()
      const symbolPath = symbol.replace('/', '-')
      const path = `/api/v1/market/ticker/${exchange}/${marketType}/${symbolPath}`
      const data = await apiGet<unknown>(creds, path)
      output(data, fmt(opts), (d) => {
        const resp = d as Record<string, unknown>
        // TickerResponse = { ticker: Ticker, stale: boolean }
        const t = (resp.ticker ?? resp) as Record<string, unknown>
        const stale = resp.stale === true ? ' (stale)' : ''
        return table(['交易对', '最新价', '买一', '卖一', '24h 量'], [
          [
            symbol + stale,
            String(t.last ?? '-'),
            String(t.bid ?? '-'),
            String(t.ask ?? '-'),
            String(t.baseVolume ?? t.quoteVolume ?? '-'),
          ],
        ])
      })
    } catch (e) {
      fail(e)
    }
  },
)

// ============================================================
// portfolio —— PortfolioSummaryView = { accounts: [{accountId, exchange, label, totalUsdt, balances}] }
// ============================================================
globalOpts(program.command('portfolio').description('组合汇总')).action(
  async (opts: { format?: string; baseUrl?: string }) => {
    try {
      const creds = resolveCreds(opts)
      const data = await apiGet<unknown>(creds, '/api/v1/portfolio/summary')
      output(data, fmt(opts), (d) => {
        const resp = d as Record<string, unknown>
        const accountList = (resp.accounts ?? []) as Array<Record<string, unknown>>
        if (accountList.length === 0) return '(空)'
        const rows = accountList.map((a) => [
          String(a.accountId ?? '-'),
          String(a.exchange ?? '-'),
          String(a.label ?? '-'),
          String(a.totalUsdt ?? '-'),
        ])
        return table(['账户ID', '交易所', '标签', '总资产(USDT)'], rows)
      })
    } catch (e) {
      fail(e)
    }
  },
)

// ============================================================
// positions —— PositionDto 无 exchange 字段(只 accountId/symbol/side/qty/avgEntryPrice/...);
// 后端要求 accountId 必填,无 --account 时 fallback 用第一个账户
// ============================================================
globalOpts(
  program
    .command('positions')
    .description('持仓列表')
    .option('-a, --account <id>', '按账户 ID 过滤(省略则用第一个账户)'),
).action(async (opts: { account?: string; format?: string; baseUrl?: string }) => {
  try {
    const creds = resolveCreds(opts)
    let accountId = opts.account
    if (!accountId) {
      const accs = await apiGet<unknown[]>(creds, '/api/v1/accounts')
      const first = accs[0] as Record<string, unknown> | undefined
      if (!first?.id) {
        console.error('未找到账户,请 kwikquant accounts list 拿 accountId 后 --account <id>')
        process.exitCode = 1
        return
      }
      accountId = String(first.id)
    }
    const data = await apiGet<unknown[]>(creds, `/api/v1/positions?accountId=${accountId}`)
    output(data, fmt(opts), (d) => {
      if (d.length === 0) return '(空)'
      const rows = d.map((p) => {
        const v = p as Record<string, unknown>
        return [
          String(v.accountId ?? '-'),
          String(v.symbol ?? '-'),
          String(v.side ?? v.positionSide ?? '-'),
          String(v.qty ?? '-'),
          String(v.avgEntryPrice ?? '-'),
          String(v.unrealizedPnl ?? '-'),
          String(v.marginMode ?? '-'),
        ]
      })
      return table(['账户', '交易对', '方向', '数量', '开仓价', '未实现盈亏', '保证金模式'], rows)
    })
  } catch (e) {
    fail(e)
  }
})

program.parse()
