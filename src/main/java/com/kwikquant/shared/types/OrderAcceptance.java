package com.kwikquant.shared.types;

import java.math.BigDecimal;

/**
 * 订单接受性纯函数层(docs/matching-spec.md §9,与撮合并列的独立层——不塞进 MatchingKernel)。
 * 判定一笔订单在给定 {@link PairSpec} 下是否可接受;accept/reject 改变可观察结果(成交 vs 拒单),
 * 与撮合同级进差分对拍({@code tests/fixtures/matching/acceptance_*.json},kind="acceptance")。
 *
 * <p>消费方:Java {@code Order.validate}(拒时抛 InvalidOrderException(message),消息语义与
 * spec §9.2 规则表逐字一致)与回测 pairSpecs 快照下发;Python 镜像 {@code kwikquant_worker/
 * acceptance.py}(回测 place_order/撮合前闸门,拒单进 warnings 不再静默)。改规则必须先改
 * spec §9 → 再改 fixtures → 再改双侧实现(CI 双门控)。
 *
 * <p><b>不含墙钟校验</b>:GTD expireAt 必须在未来等时间相关校验留在 Order.validate
 * (fixtures 不可复现,spec §9.1)。本类无状态、无异常抛出——校验失败以 {@link AcceptResult}
 * 值返回,由消费方决定异常语义。
 */
public final class OrderAcceptance {

    /**
     * PERP 杠杆全局保守上限。per-symbol 真实上限以交易所声明({@link PairSpec#maxLeverage()},
     * OKX swap 实测 100)为准且 fail-closed 必填;本常量只兜"声明值异常大"的脏数据。
     * 单一真相源(spec §9.2 规则 12),Python 侧 acceptance.MAX_LEVERAGE_CAP 镜像。
     */
    public static final int MAX_LEVERAGE_CAP = 100;

    private OrderAcceptance() {}

    /**
     * 接受性判定输入(spec §9.1)。不含 timeInForce/expireAt——墙钟相关校验不进本层。
     * PERP 的 side 可 null(单源 positionEffect 派生);SPOT 必填且合约字段必须全 null。
     */
    public record Input(
            String symbol,
            MarketType marketType,
            OrderSide side,
            OrderType orderType,
            BigDecimal amount,
            BigDecimal price,
            BigDecimal stopPrice,
            Integer leverage,
            MarginMode marginMode,
            PositionEffect positionEffect) {}

    /**
     * 判定输出。接受时 {@code ok=true, reasonCode=null, message=null};拒绝时 reasonCode 属
     * spec §9.2 枚举,message 逐字(两侧字符串化规则一致,输入字面量原样、无算术派生值)。
     */
    public record AcceptResult(boolean ok, String reasonCode, String message) {

        static final AcceptResult ACCEPTED = new AcceptResult(true, null, null);

        static AcceptResult rejected(String reasonCode, String message) {
            return new AcceptResult(false, reasonCode, message);
        }
    }

    /**
     * 规则表顺序敏感,命中即终止(spec §9.2 规则 1-19)。{@code pairSpec == null} = 交易对未知,
     * fail-closed 拒(规则 4)。marketType 非 PERP(含 null)走 SPOT 分支——与重构前
     * Order.validate 行为一致。
     */
    public static AcceptResult check(Input input, PairSpec pairSpec) {
        if (input.symbol() == null || input.symbol().isBlank()) {
            return AcceptResult.rejected("SYMBOL_BLANK", "symbol is blank");
        }
        if (input.orderType() == null) {
            return AcceptResult.rejected("ORDER_TYPE_REQUIRED", "orderType is required");
        }
        if (input.amount() == null || input.amount().signum() <= 0) {
            return AcceptResult.rejected("AMOUNT_POSITIVE", "amount must be positive");
        }
        if (pairSpec == null) {
            return AcceptResult.rejected("UNKNOWN_SYMBOL", "unknown symbol: " + input.symbol());
        }
        if (pairSpec.minQty() != null && input.amount().compareTo(pairSpec.minQty()) < 0) {
            return AcceptResult.rejected("MIN_QTY", "amount " + input.amount() + " < minQty " + pairSpec.minQty());
        }
        if (pairSpec.maxQty() != null && input.amount().compareTo(pairSpec.maxQty()) > 0) {
            return AcceptResult.rejected("MAX_QTY", "amount " + input.amount() + " > maxQty " + pairSpec.maxQty());
        }
        // 对齐即保证 PERP 出站张数 sz = amount/contractSize 是 lotSz 整数倍(stepSize 已币化)
        if (pairSpec.stepSize() != null && pairSpec.stepSize().signum() > 0) {
            BigDecimal mod = input.amount().remainder(pairSpec.stepSize());
            if (mod.signum() != 0) {
                return AcceptResult.rejected(
                        "STEP_SIZE", "amount " + input.amount() + " not aligned to stepSize " + pairSpec.stepSize());
            }
        }
        boolean needsPrice = input.orderType() == OrderType.LIMIT
                || input.orderType() == OrderType.STOP_LIMIT
                || input.orderType() == OrderType.TAKE_PROFIT_LIMIT;
        if (needsPrice && (input.price() == null || input.price().signum() <= 0)) {
            return AcceptResult.rejected("PRICE_REQUIRED", "price required for " + input.orderType());
        }
        boolean needsStopPrice = input.orderType() == OrderType.STOP_MARKET
                || input.orderType() == OrderType.STOP_LIMIT
                || input.orderType() == OrderType.TAKE_PROFIT_MARKET
                || input.orderType() == OrderType.TAKE_PROFIT_LIMIT;
        if (needsStopPrice && (input.stopPrice() == null || input.stopPrice().signum() <= 0)) {
            return AcceptResult.rejected("STOP_PRICE_REQUIRED", "stopPrice required for " + input.orderType());
        }
        if (input.price() != null
                && pairSpec.tickSize() != null
                && pairSpec.tickSize().signum() > 0) {
            BigDecimal mod = input.price().remainder(pairSpec.tickSize());
            if (mod.signum() != 0) {
                return AcceptResult.rejected(
                        "TICK_SIZE", "price " + input.price() + " not aligned to tickSize " + pairSpec.tickSize());
            }
        }
        if (input.stopPrice() != null
                && pairSpec.tickSize() != null
                && pairSpec.tickSize().signum() > 0) {
            BigDecimal mod = input.stopPrice().remainder(pairSpec.tickSize());
            if (mod.signum() != 0) {
                return AcceptResult.rejected(
                        "TICK_SIZE",
                        "stopPrice " + input.stopPrice() + " not aligned to tickSize " + pairSpec.tickSize());
            }
        }
        if (input.marketType() == MarketType.PERP) {
            return checkPerp(input, pairSpec);
        }
        if (input.side() == null) {
            return AcceptResult.rejected("SIDE_REQUIRED", "side is required");
        }
        if (input.leverage() != null || input.marginMode() != null || input.positionEffect() != null) {
            return AcceptResult.rejected(
                    "SPOT_CONTRACT_FIELDS", "SPOT order must not set leverage/marginMode/positionEffect");
        }
        return AcceptResult.ACCEPTED;
    }

    private static AcceptResult checkPerp(Input input, PairSpec pairSpec) {
        if (input.leverage() == null || input.leverage() < 1 || input.leverage() > MAX_LEVERAGE_CAP) {
            return AcceptResult.rejected(
                    "LEVERAGE_RANGE", "PERP leverage must be 1-" + MAX_LEVERAGE_CAP + ", got: " + input.leverage());
        }
        if (input.marginMode() == null) {
            return AcceptResult.rejected("MARGIN_MODE_REQUIRED", "PERP marginMode required (ISOLATED/CROSS)");
        }
        if (input.positionEffect() == null) {
            return AcceptResult.rejected(
                    "POSITION_EFFECT_REQUIRED",
                    "PERP positionEffect required (OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT)");
        }
        // 四象限一致性:side 单源=positionEffect(派生表 PositionEffect.toSide)。显式传入的 side
        // 与派生值矛盾(如 SELL+OPEN_LONG)即拒——矛盾输入会静默开出与用户意图相反的仓。
        OrderSide derivedSide = input.positionEffect().toSide();
        if (input.side() != null && input.side() != derivedSide) {
            return AcceptResult.rejected(
                    "SIDE_EFFECT_MISMATCH",
                    "side " + input.side() + " contradicts positionEffect " + input.positionEffect()
                            + " (expected side " + derivedSide + ")");
        }
        // per-symbol maxLeverage pre-trade 校验(来自 CCXT market.limits.leverage.max)。
        // PAPER/回测无交易所拒单兜底,缺此校验会撮合超杠杆单破坏真实性;PERP fail-closed:
        // 交易所未声明上限即拒单,不用兜底值放行(pair 装载已修 type 过滤,真实 OKX swap 恒声明 lever)。
        if (pairSpec.maxLeverage() == null) {
            return AcceptResult.rejected(
                    "MAX_LEVERAGE_UNDECLARED",
                    "PERP maxLeverage not declared for " + input.symbol() + ", refusing (fail-closed)");
        }
        if (input.leverage() > pairSpec.maxLeverage()) {
            return AcceptResult.rejected(
                    "MAX_LEVERAGE",
                    "leverage " + input.leverage() + " exceeds maxLeverage " + pairSpec.maxLeverage() + " for "
                            + input.symbol());
        }
        return AcceptResult.ACCEPTED;
    }
}
