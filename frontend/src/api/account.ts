import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * account typed client。
 *
 * 端点(均 JWT):
 *  - GET    /api/v1/accounts              → ExchangeAccountView[](当前用户名下账户,apiKey 脱敏)
 *  - POST   /api/v1/accounts              → ExchangeAccountView(创建,API key AES-256-GCM 加密存储,响应脱敏)
 *  - PUT    /api/v1/accounts/{id}         → ExchangeAccountView(更新 label/apiKey/passphrase,备用)
 *  - DELETE /api/v1/accounts/{id}         → 204(删除,越权 403/1002)
 *  - GET    /api/v1/accounts/{id}/balance → BalanceSnapshot(余额快照,交易所不可用 502/6001)
 *
 * honest:ExchangeAccountView 无余额字段(只 id/exchange/label/apiKey/paperTrading/status),
 * 余额走 per-card GET /accounts/{id}/balance → BalanceSnapshot.currencies{USDT:{free,used,total}}。
 * free=可用 / used=冻结 / total=总权益。单币种账户简化取 USDT 项;多币种取 USDT 估值或首项。
 */
type ExchangeAccountView = components['schemas']['ExchangeAccountView']
type CreateAccountRequest = components['schemas']['CreateAccountRequest']
type BalanceSnapshot = components['schemas']['BalanceSnapshot']

/** 更新账户 body(PortfolioPage 无编辑 UI,备用;后端 schema 名待确认,先自定)。 */
export interface AccountUpdateRequest {
  label?: string
  apiKey?: string
  apiSecret?: string
  passphrase?: string
}

/** 查询当前用户交易所账户列表(apiKey 脱敏末4位)。 */
export function fetchAccounts(): Promise<ExchangeAccountView[]> {
  return apiFetch<ExchangeAccountView[]>('/api/v1/accounts')
}

/** 查询账户余额快照(per-card,ExchangeAccountView 无余额字段)。 */
export function fetchAccountBalance(id: number): Promise<BalanceSnapshot> {
  return apiFetch<BalanceSnapshot>(`/api/v1/accounts/${id}/balance`)
}

/** 创建交易所账户(API key 加密存储,响应脱敏)。label 重复/格式非法 400(3001)。 */
export function createAccount(body: CreateAccountRequest): Promise<ExchangeAccountView> {
  return apiFetch<ExchangeAccountView>('/api/v1/accounts', { method: 'POST', body })
}

/** 更新交易所账户(PortfolioPage 无编辑 UI,备用)。 */
export function updateAccount(id: number, body: AccountUpdateRequest): Promise<ExchangeAccountView> {
  return apiFetch<ExchangeAccountView>(`/api/v1/accounts/${id}`, { method: 'PUT', body })
}

/**
 * 删除账户(DELETE → 204 No Content)。
 * 204 无 body,apiFetch parseBody(res.json)抛 SyntaxError,catch 放行。
 */
export async function deleteAccount(id: number): Promise<void> {
  try {
    await apiFetch<void>(`/api/v1/accounts/${id}`, { method: 'DELETE' })
  } catch (e) {
    if (e instanceof SyntaxError) return
    throw e
  }
}
