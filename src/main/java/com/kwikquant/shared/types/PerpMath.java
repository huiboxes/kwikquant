package com.kwikquant.shared.types;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PERP 纯数学内核(无 Spring、无状态),语义单一真相源 = {@code docs/perp-math-spec.md}。
 * 域内规范单位=币数量(base coin),张数(contracts)只存在于交易所边界适配器内部——
 * {@link #toContracts}/{@link #toCoin} 是张↔币换算的唯一端口。
 *
 * <p>定点纪律(spec §2):金额/数量 scale 8、HALF_UP(远离零);每次除法显式指定 scale 与舍入;
 * EXACT 类函数(精确加减乘/整除)不额外舍入。例外是 {@link #toContracts}:合约张数换算要求
 * <b>精确整除</b>,除不尽即抛 {@link ArithmeticException}(fail-closed)——静默取整会让实际
 * 下单量偏离用户意图。
 *
 * <p>Python 侧对等实现在 {@code kwikquant_worker/perp_math.py},双侧经
 * {@code tests/fixtures/perp/} 差分 fixtures 对拍(CI 双门控:JUnit {@code PerpMathFixturesTest}
 * + pytest {@code test_perp_math_fixtures.py})。修改任何函数的运算顺序/舍入点/校验必须
 * 先改规范与 fixtures,再同步双侧实现(spec §6)。
 */
public final class PerpMath {

    private PerpMath() {}

    /** 落账/发布定标 scale(spec §2)。 */
    private static final int MONEY_SCALE = 8;

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * 简化维持保证金率默认值(0.5%,近似 OKX 最低档;实际随档位变化)。单源在此,
     * {@code Position.DEFAULT_MAINT_MARGIN_RATE} 是别名;CROSS 账户级聚合与 ISOLATED
     * 逐仓强平价共用,避免多处硬编码漂移。
     */
    public static final BigDecimal DEFAULT_MAINT_MARGIN_RATE = new BigDecimal("0.005");

    /** positionSide 规范值(与 {@code Position.positionSide} / OKX posSide 一致)。 */
    public static final String SIDE_LONG = "LONG";

    public static final String SIDE_SHORT = "SHORT";

    /**
     * 币数量 → 张数(出站下单边界):{@code sz = coinAmount / contractSize},要求精确整除。
     * amount 已按币化 stepSize(= lotSz × contractSize)对齐时天然整除;除不尽说明调用方
     * 绕过了 Order.validate 的 stepSize 校验,fail-closed 抛 {@link ArithmeticException}。
     *
     * @param coinAmount   币数量(如 0.0001 BTC)
     * @param contractSize 单张合约的币数量(OKX ctVal,如 0.01;SPOT 恒 1)
     */
    public static BigDecimal toContracts(BigDecimal coinAmount, BigDecimal contractSize) {
        requirePositive(coinAmount, "coinAmount");
        requirePositive(contractSize, "contractSize");
        // 无 scale 的 divide:精确商,除不尽抛 ArithmeticException(fail-closed,禁止静默取整)
        return coinAmount.divide(contractSize);
    }

    /**
     * 张数 → 币数量(回流解析边界):{@code coin = contracts × contractSize}。乘法精确,无舍入。
     *
     * <p><b>允许 0</b>:OKX 对未成交订单恒返 {@code fillSz="0"}、双向持仓/平仓窗口可出现 {@code pos="0"},
     * 快照回流必须能换算零值(0 张 = 0 币);拒 0 会让撤单/对账路径在未成交单上全线炸掉。
     * 成交事件语义的"必须为正"由下游(Order.accumulateFill)把关,不在换算层重复。
     *
     * @param contracts    张数(如 OKX fillSz/pos/sz,≥0)
     * @param contractSize 单张合约的币数量(>0)
     */
    public static BigDecimal toCoin(BigDecimal contracts, BigDecimal contractSize) {
        requireNonNegative(contracts, "contracts");
        requirePositive(contractSize, "contractSize");
        return contracts.multiply(contractSize);
    }

    /**
     * 方向化数量增量(净持仓模式账本用,spec §3.3):BUY → +qty,SELL → −qty。EXACT 不舍入。
     */
    public static BigDecimal signedDelta(OrderSide side, BigDecimal qty) {
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        requirePositive(qty, "qty");
        return side == OrderSide.BUY ? qty : qty.negate();
    }

    /**
     * 开仓初始保证金(逐仓,不区分多空方向,spec §3.4):
     * {@code (price × qty) / leverage},乘积精确,除法 scale 8 HALF_UP。
     */
    public static BigDecimal initialMargin(BigDecimal price, BigDecimal qty, int leverage) {
        requirePositive(price, "price");
        requirePositive(qty, "qty");
        requireLeverage(leverage);
        return price.multiply(qty).divide(BigDecimal.valueOf(leverage), MONEY_SCALE, ROUNDING);
    }

    /**
     * 单仓维持保证金(CROSS 账户级聚合的加数,spec §3.5):{@code markPrice × qty × mmr}。
     * EXACT 不舍入——逐仓不舍入、聚合后一次比较,避免逐仓 dust 累积。
     */
    public static BigDecimal maintenanceMarginRequired(
            BigDecimal markPrice, BigDecimal qty, BigDecimal maintMarginRate) {
        requirePositive(markPrice, "markPrice");
        requirePositive(qty, "qty");
        requireRate(maintMarginRate);
        return markPrice.multiply(qty).multiply(maintMarginRate);
    }

    /**
     * 逐仓简化强平价,margin-aware(spec §3.6):保证金余额恰好跌到维持保证金时的标记价。
     * 输入是持仓数量与保证金(不是杠杆)——保证金被资金费侵蚀后杠杆式公式失真。
     * <b>运算顺序影响结果</b>,两侧实现必须保持:
     * <pre>
     *   notional = avgEntryPrice × qty                       # 精确
     *   LONG:  liq = (notional − margin) / (qty × (1 − mmr)) # 分子/分母精确
     *   SHORT: liq = (notional + margin) / (qty × (1 + mmr)) # 除法 scale 8 HALF_UP(普遍非终止)
     * </pre>
     *
     * <p>margin 允许负(资金费可把仓位保证金侵蚀穿仓);结果可 ≤0(保证金耗尽)——此时
     * 价格比较永不触发,强平<b>触发判定一律用 {@link #marginBreached}</b>,本函数输出只作
     * 展示/参考价(positions.liquidation_price 列、对账 fallback)。
     *
     * <p>简化公式与 OKX 实盘强平价有偏差(档位 mmr、费用补偿等),PAPER/回测近似用。
     * CROSS 无单仓强平价——调用方 guard(见 {@code Position.computeLiquidationPrice})。
     *
     * @param positionSide {@value #SIDE_LONG} / {@value #SIDE_SHORT}
     */
    public static BigDecimal liquidationPriceIsolated(
            BigDecimal avgEntryPrice,
            BigDecimal qty,
            BigDecimal margin,
            BigDecimal maintMarginRate,
            String positionSide) {
        requirePositive(avgEntryPrice, "avgEntryPrice");
        requirePositive(qty, "qty");
        if (margin == null) {
            throw new IllegalArgumentException("margin must not be null");
        }
        requireRate(maintMarginRate);
        boolean shortSide = requirePositionSide(positionSide);
        BigDecimal notional = avgEntryPrice.multiply(qty);
        BigDecimal numerator = shortSide ? notional.add(margin) : notional.subtract(margin);
        BigDecimal denominatorFactor =
                shortSide ? BigDecimal.ONE.add(maintMarginRate) : BigDecimal.ONE.subtract(maintMarginRate);
        return numerator.divide(qty.multiply(denominatorFactor), MONEY_SCALE, ROUNDING);
    }

    /**
     * 保证金穿仓触发谓词(spec §3.7):{@code marginBalance ≤ 0 || maintMarginRequired ≥ marginBalance}。
     * CROSS 账户级与 ISOLATED 单仓共用;边界语义:相等即触发。
     *
     * <p>ISOLATED 强平判定必须用本谓词而非 markPrice 与存量强平价列的价格比较:
     * 资金费侵蚀后 LONG 强平价可 ≤0({@link #liquidationPriceIsolated}),价格比较永不触发,
     * 仓位会被放血至穿仓而不强平。
     */
    public static boolean marginBreached(BigDecimal marginBalance, BigDecimal maintMarginRequired) {
        if (marginBalance == null) {
            throw new IllegalArgumentException("marginBalance must not be null");
        }
        requireNonNegative(maintMarginRequired, "maintMarginRequired");
        return marginBalance.signum() <= 0 || maintMarginRequired.compareTo(marginBalance) >= 0;
    }

    /**
     * 单期资金费结算金额,<b>从持仓视角</b>带符号(spec §3.8):正=收(加余额),负=付(扣余额)。
     * <pre>
     *   sideSign = SHORT ? +1 : −1
     *   fundingAmount = fundingRate × markPrice × qty × sideSign   # setScale(8, HALF_UP)
     * </pre>
     * 符号约定(OKX 语义):正费率多头付空头收,负费率反转。fundingRate 可为负、可为 0,不校验范围。
     * positionSide 非 LONG/SHORT 抛 INVALID_SIDE(fail-closed,调用方跳过结算,不猜方向)。
     * 期次网格(8h/interval、SETTLED vs 预估)不属于本内核。
     */
    public static BigDecimal fundingAmount(
            String positionSide, BigDecimal fundingRate, BigDecimal markPrice, BigDecimal qty) {
        boolean shortSide = requirePositionSide(positionSide);
        if (fundingRate == null) {
            throw new IllegalArgumentException("fundingRate must not be null");
        }
        requirePositive(markPrice, "markPrice");
        requirePositive(qty, "qty");
        BigDecimal sideSign = shortSide ? BigDecimal.ONE : BigDecimal.valueOf(-1);
        return fundingRate.multiply(markPrice).multiply(qty).multiply(sideSign).setScale(MONEY_SCALE, ROUNDING);
    }

    /**
     * 平仓已实现盈亏(毛额,费用不入本函数,spec §3.9):LONG {@code (exit − avg) × qty},
     * SHORT {@code (avg − exit) × qty}。EXACT 不舍入,结果可为负(亏损)。
     */
    public static BigDecimal closedPnl(
            String positionSide, BigDecimal avgEntryPrice, BigDecimal exitPrice, BigDecimal closeQty) {
        boolean shortSide = requirePositionSide(positionSide);
        requirePositive(avgEntryPrice, "avgEntryPrice");
        requirePositive(exitPrice, "exitPrice");
        requirePositive(closeQty, "closeQty");
        BigDecimal diff = shortSide ? avgEntryPrice.subtract(exitPrice) : exitPrice.subtract(avgEntryPrice);
        return diff.multiply(closeQty);
    }

    /**
     * 加仓后的加权平均开仓价(spec §3.10):
     * {@code (oldAvg × oldQty + fillPrice × fillQty) / (oldQty + fillQty)},scale 8 HALF_UP。
     * oldQty = 0 的建仓场景不走本函数(直接用 fillPrice,见 {@link #applyPositionDelta})。
     */
    public static BigDecimal weightedAvgEntryPrice(
            BigDecimal oldAvgEntryPrice, BigDecimal oldQty, BigDecimal fillPrice, BigDecimal fillQty) {
        requirePositive(oldAvgEntryPrice, "oldAvgEntryPrice");
        requirePositive(oldQty, "oldQty");
        requirePositive(fillPrice, "fillPrice");
        requirePositive(fillQty, "fillQty");
        BigDecimal totalCost = oldAvgEntryPrice.multiply(oldQty).add(fillPrice.multiply(fillQty));
        return totalCost.divide(oldQty.add(fillQty), MONEY_SCALE, ROUNDING);
    }

    /**
     * 平仓释放的冻结保证金(spec §3.11):全平精确释放 currentFrozenMargin(EXACT,免除法 dust);
     * 部分平按比例 {@code currentFrozenMargin × closeQty / currentQty}(scale 8 HALF_UP)。
     */
    public static BigDecimal frozenMarginRelease(
            BigDecimal currentFrozenMargin, BigDecimal currentQty, BigDecimal closeQty) {
        requireNonNegative(currentFrozenMargin, "currentFrozenMargin");
        requirePositive(currentQty, "currentQty");
        requirePositive(closeQty, "closeQty");
        if (closeQty.compareTo(currentQty) > 0) {
            throw new IllegalArgumentException(
                    "PERP CLOSE over-position: closeQty=" + closeQty + " > qty=" + currentQty);
        }
        if (closeQty.compareTo(currentQty) == 0) {
            return currentFrozenMargin;
        }
        return currentFrozenMargin.multiply(closeQty).divide(currentQty, MONEY_SCALE, ROUNDING);
    }

    /**
     * {@link #applyPositionDelta} 输出契约(spec §3.12)。{@code newAvgEntryPrice} 全平时为 null;
     * {@code marginDelta} 正=冻结增加(开仓),负=释放(平仓);调用方持久化
     * {@code newFrozen = currentFrozenMargin + marginDelta}。
     */
    public record PositionDelta(
            BigDecimal newQty, BigDecimal newAvgEntryPrice, BigDecimal realizedPnlDelta, BigDecimal marginDelta) {}

    /**
     * 单向桶(LONG 或 SHORT)内叠加一笔成交,三段语义(spec §3.12):建仓/加仓(open)、
     * 部分减仓(close 且 newQty>0)、全平(close 且 newQty==0,avg 置 null、marginDelta 恰为
     * −currentFrozenMargin)。无反手——反向意图由调用方拆成 CLOSE + OPEN 两笔。
     *
     * <p>字段簿记(side 字符串、全平清理、强平价重算触发)与四向 {@code PositionEffect} →
     * {@code (positionSide, open)} 映射属于调用方(Java 侧 {@code PositionService.applyPerpDelta})。
     *
     * @param positionSide         {@value #SIDE_LONG} / {@value #SIDE_SHORT}
     * @param open                 true=开仓/加仓,false=平仓
     * @param currentQty           当前桶持仓量(≥0,null 由调用方归零后传入)
     * @param currentAvgEntryPrice 当前均价(仅参与运算的分支校验:建仓不用,加仓/平仓必须 >0)
     * @param currentFrozenMargin  当前冻结保证金(≥0)
     * @param fillQty              成交量(>0)
     * @param fillPrice            成交价(>0)
     * @param leverage             杠杆(≥1,两分支统一校验;close 分支不参与运算)
     */
    public static PositionDelta applyPositionDelta(
            String positionSide,
            boolean open,
            BigDecimal currentQty,
            BigDecimal currentAvgEntryPrice,
            BigDecimal currentFrozenMargin,
            BigDecimal fillQty,
            BigDecimal fillPrice,
            int leverage) {
        requirePositionSide(positionSide);
        requireNonNegative(currentQty, "currentQty");
        requireNonNegative(currentFrozenMargin, "currentFrozenMargin");
        requirePositive(fillQty, "fillQty");
        requirePositive(fillPrice, "fillPrice");
        requireLeverage(leverage);
        if (open) {
            BigDecimal newQty = currentQty.add(fillQty);
            BigDecimal newAvg = currentQty.signum() == 0
                    ? fillPrice
                    : weightedAvgEntryPrice(currentAvgEntryPrice, currentQty, fillPrice, fillQty);
            return new PositionDelta(newQty, newAvg, BigDecimal.ZERO, initialMargin(fillPrice, fillQty, leverage));
        }
        if (fillQty.compareTo(currentQty) > 0) {
            throw new IllegalArgumentException("PERP CLOSE over-position: fillQty=" + fillQty + " > qty=" + currentQty);
        }
        BigDecimal realizedPnlDelta = closedPnl(positionSide, currentAvgEntryPrice, fillPrice, fillQty);
        BigDecimal newQty = currentQty.subtract(fillQty);
        BigDecimal release = frozenMarginRelease(currentFrozenMargin, currentQty, fillQty);
        return new PositionDelta(
                newQty, newQty.signum() == 0 ? null : currentAvgEntryPrice, realizedPnlDelta, release.negate());
    }

    /** positionSide 校验 + 派生(LONG→false / SHORT→true);其余值(含 null)fail-closed。 */
    private static boolean requirePositionSide(String positionSide) {
        if (SIDE_LONG.equals(positionSide)) {
            return false;
        }
        if (SIDE_SHORT.equals(positionSide)) {
            return true;
        }
        throw new IllegalArgumentException("positionSide must be LONG or SHORT, got: " + positionSide);
    }

    private static void requireLeverage(int leverage) {
        if (leverage < 1) {
            throw new IllegalArgumentException("leverage must be >= 1, got: " + leverage);
        }
    }

    private static void requireRate(BigDecimal maintMarginRate) {
        if (maintMarginRate == null
                || maintMarginRate.signum() <= 0
                || maintMarginRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("maintMarginRate must be in (0, 1), got: " + maintMarginRate);
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + value);
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative, got: " + value);
        }
    }
}
