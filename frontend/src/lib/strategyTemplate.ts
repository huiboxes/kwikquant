/**
 * 策略模板代码(spec §5 step 13,F6.2)。
 *
 * 新建策略时 Monaco 编辑器默认预填此模板,降低上手成本。
 * 实际 kwikquant_worker API 以 docs/design-ref/ 为准,此处是骨架示例。
 */
export const STRATEGY_TEMPLATE = `# KwikQuant 策略模板
# 文档: https://docs.kwikquant.dev/strategy
# 生命周期: on_init → on_bar(每根 K 线)→ on_tick(可选,实盘)→ on_stop

from kwikquant import Strategy


class MyStrategy(Strategy):
    """示例:简单双均线交叉(DRAFT,需回测验证后发布)"""

    fast_period = 10
    slow_period = 30

    def on_init(self, ctx):
        ctx.log("策略初始化")

    def on_bar(self, bar):
        fast = ctx.sma(period=self.fast_period)
        slow = ctx.sma(period=self.slow_period)
        if fast > slow and not ctx.position:
            ctx.buy(qty=0.01)
        elif fast < slow and ctx.position:
            ctx.sell(qty=ctx.position.qty)

    def on_stop(self, ctx):
        ctx.log("策略停止")
`
