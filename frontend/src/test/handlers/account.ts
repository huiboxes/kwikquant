import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * account MSW handlers。
 * mock 数据照原型 PortfolioPage data.accounts 适配 ExchangeAccountView。
 * 约定:id 1/3 = 模拟盘(paperTrading true,exchange 即行情基准),id 2/4 = LIVE 实盘。
 * 余额走 per-card /balance → BalanceSnapshot.currencies{USDT:{free,used,total}}。
 * free=可用 / used=冻结 / total=总权益(单币种 USDT 账户简化)。
 */
type ExchangeAccountView = components['schemas']['ExchangeAccountView']
type BalanceSnapshot = components['schemas']['BalanceSnapshot']
type CreateAccountRequest = components['schemas']['CreateAccountRequest']

const ACCOUNTS: ExchangeAccountView[] = [
  { id: 1, exchange: 'BINANCE', label: 'BINANCE 模拟', apiKey: '', paperTrading: true, status: 'ACTIVE' },
  { id: 2, exchange: 'BINANCE', label: '主账户', apiKey: '...a1b2', paperTrading: false, status: 'ACTIVE' },
  { id: 3, exchange: 'OKX', label: 'OKX 模拟', apiKey: '', paperTrading: true, status: 'ACTIVE' },
  { id: 4, exchange: 'OKX', label: 'OKX 实盘', apiKey: '...c3d4', paperTrading: false, status: 'ACTIVE' },
]

const BALANCES: Record<number, BalanceSnapshot> = {
  1: { currencies: { USDT: { free: 100000, used: 0, total: 100000 } } },
  2: { currencies: { USDT: { free: 4800, used: 434.18, total: 5234.18 } } },
  3: { currencies: { USDT: { free: 95000, used: 5000, total: 100000 } } },
  4: { currencies: { USDT: { free: 890.5, used: 0, total: 890.5 } } },
}

let nextId = 5

export const accountHandlers = [
  // GET /api/v1/accounts → 当前用户账户列表(apiKey 脱敏)
  http.get('/api/v1/accounts', () => {
    return HttpResponse.json(envelope(ACCOUNTS))
  }),

  // GET /api/v1/accounts/:id/balance → 余额快照
  http.get('/api/v1/accounts/:id/balance', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const bal = BALANCES[id]
    if (!bal) {
      return HttpResponse.json(envelope(null, 4001, '账户不存在'), { status: 404 })
    }
    return HttpResponse.json(envelope(bal))
  }),

  // POST /api/v1/accounts → 创建(apiKey 脱敏,PAPER 给 10 万虚拟资金)
  http.post('/api/v1/accounts', async ({ request }) => {
    const body = (await request.json()) as CreateAccountRequest
    const isPaper = body.paperTrading
    const newAcc: ExchangeAccountView = {
      id: nextId,
      exchange: body.exchange,
      label: body.label,
      apiKey: isPaper ? '' : `...${body.apiKey.slice(-4)}`,
      paperTrading: isPaper,
      status: 'ACTIVE',
    }
    ACCOUNTS.push(newAcc)
    BALANCES[nextId] = {
      currencies: { USDT: { free: isPaper ? 100000 : 0, used: 0, total: isPaper ? 100000 : 0 } },
    }
    nextId += 1
    return HttpResponse.json(envelope(newAcc), { status: 201 })
  }),

  // DELETE /api/v1/accounts/:id → 204(无 body,apiFetch parseBody 抛 SyntaxError 由 deleteAccount catch 放行)
  http.delete('/api/v1/accounts/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)
    const idx = ACCOUNTS.findIndex((a) => a.id === id)
    if (idx < 0) {
      return HttpResponse.json(envelope(null, 4001, '账户不存在'), { status: 404 })
    }
    ACCOUNTS.splice(idx, 1)
    delete BALANCES[id]
    return new HttpResponse(null, { status: 204 })
  }),
]
