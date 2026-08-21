/**
 * 防御性 strip 合约后缀(BTC/USDT:USDT → BTC/USDT)。
 *
 * 后端 canonical 现货本就是干净 `BTC/USDT`(CcxtTickerAdapter 用传入 canonical);
 * 但 TradingPairService.parseMarket 直接透传 markets dict raw symbol，合约实例可能含 `:USDT`。
 * 策略页固定现货，正常不会出现后缀；此函数兜底，确保任何透传都不会把后缀暴露给用户。
 * 现货 symbol 本无后缀，replace 无副作用。
 */
export function stripContractSuffix(sym: string | null | undefined): string {
  if (!sym) return ''
  return sym.replace(/:USDT$/, '')
}
