/**
 * OKX Unified Tokenized Stocks base asset 集合(2026-07-15 OKX 公告,X 前缀 + 美股代码)。
 *
 * 来源:OKX 官方公告确认的 24 个 + 真实 `/market/pairs` 拉取的 X 前缀标的中
 * 确定是美股/ETF 代码的 12 个(XASTS/XBI/XCOIN/XDELL/XHOOD/XIBM/XIREN/XLE/XLLY/XNFLX/XORCL/XPLTR)。
 *
 * 排除(非股票,即使 X 前缀也不进白名单):
 *  - 加密币:XRP(Ripple)/XLM(Stellar)/XTZ(Tezos)/XCH(Chia)/XAUT(Tether Gold 代币化黄金)
 *  - 贵金属 ISO 代码:XAG(银)/XAU(金)/XPT(铂)/XPD(钯)/XCU(铜)
 *  - 代码未确认:XBMNR/XUSAR/XPL(漏标可接受)
 *
 * 原则:**零误判**(白名单只含确定的股票代币,XRP 必返 false);漏标可接受
 * (漏标只是没标记,用户切 PERP 才发现,非本方案引入)。
 *
 * 维护:OKX 后续上架新 tokenized stocks 时,从 `/market/pairs` 拉 X 前缀标的,
 * 排除加密币/贵金属 ISO 后补入。follow-up 可做定时拉取自动维护。
 */
export const STOCK_TOKENS: ReadonlySet<string> = new Set([
  // OKX 官方公告确认
  'XMU', 'XSPCX', 'XSNDK', 'XSKHY', 'XSPY', 'XQQQ', 'XNVDA', 'XTSLA',
  'XMRVL', 'XINTC', 'XSOXL', 'XGOOGL', 'XMSFT', 'XCRCL', 'XAAPL', 'XEWY',
  'XAMD', 'XAMZN', 'XMETA', 'XMSTR', 'XLITE', 'XAVGO', 'XTSM', 'XIWM',
  // 真实 /market/pairs 拉取 + 美股/ETF 代码确认
  'XASTS', 'XBI', 'XCOIN', 'XDELL', 'XHOOD', 'XIBM', 'XIREN', 'XLE',
  'XLLY', 'XNFLX', 'XORCL', 'XPLTR',
])

/** 判断 canonical symbol(如 XAAPL/USDT)是否 OKX 股票代币(拆 / 取 base 查白名单)。 */
export function isStockToken(symbol: string | undefined | null): boolean {
  if (!symbol) return false
  const base = symbol.includes('/') ? symbol.split('/')[0] : symbol
  return STOCK_TOKENS.has(base)
}

/**
 * 切市场类型是否被允许。股票代币禁切 PERP(OKX 无股票合约,切了拉不到行情 + 开仓失败)。
 * 返 true 允许切,false 拒绝(caller 应 toast 提示「股票标的无合约,仅支持现货」)。
 *
 * 关键:不误拦加密币 — XRP/XLM 等 X 开头加密币 not in STOCK_TOKENS,isStockToken=false,允许切 PERP。
 */
export function canSwitchMarketType(
  symbol: string | undefined | null,
  target: 'SPOT' | 'PERP',
): boolean {
  if (target === 'PERP' && isStockToken(symbol)) return false
  return true
}
