#!/usr/bin/env node
import { Command } from 'commander'
import { loadCredentials, saveCredentials, clearCredentials, checkPermissions } from './config.js'
import { apiGet } from './client.js'
import { output, table } from './output.js'
import { globalOpts, fmt, resolveCreds, fail } from './shared.js'
import { registerMarket } from './market.js'
import { registerOrders } from './orders.js'
import { registerPortfolio } from './portfolio.js'
import { registerStrategy } from './strategy.js'
import { registerRisk } from './risk.js'

/**
 * KwikQuant CLI — 直连加密量化后端 REST(/api/v1/**),JWT 鉴权。
 *
 * 对标长桥 longbridge-terminal(130+ 命令),聚焦加密域:
 * 行情(quote/kline/depth/pairs/tickers)/ 账户(accounts)/ 组合(portfolio/positions/history)/
 * 订单(orders/order/fills + submit/cancel)/ 持仓写(position close)/ 策略(strategies/strategy/backtests)/
 * 风控(risk policies/decisions)。写操作 PAPER 免确认 / LIVE 须 --confirm。
 */
const program = new Command()
program
  .name('kwikquant')
  .description('KwikQuant 命令行工具——直连加密量化后端(行情 / 账户 / 组合 / 订单 / 策略 / 风控)')
  .version('0.2.0')

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
// accounts — 交易所账户
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
// 挂载各域子命令
// ============================================================
registerMarket(program)
registerOrders(program)
registerPortfolio(program)
registerStrategy(program)
registerRisk(program)

program.parse()
