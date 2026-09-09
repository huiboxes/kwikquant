package com.kwikquant.trading.domain;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.PerpMath;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 持仓聚合根。{@code (accountId, symbol)} 唯一。
 *
 * <p>{@code side} 取值: {@code long} / {@code short} / {@code flat}（字符串而非 enum，便于 DB 直接存）。
 *
 * <p>合约场景: side=long qty=0 等同 flat；现货场景: side 始终 long 或 flat。
 */
public class Position {

    public static final String SIDE_LONG = "long";
    public static final String SIDE_SHORT = "short";
    public static final String SIDE_FLAT = "flat";

    /** 逐仓简化维持保证金率默认值(0.5%,近似 OKX 最低档;实际随档位变化)。单源在 {@link PerpMath#DEFAULT_MAINT_MARGIN_RATE},此处为兼容别名;CROSS 账户级 + ISOLATED 逐仓强平价共用此真相源(DRY,避免 CrossLiquidationChecker/PositionService 双硬编码漂移)。 */
    public static final BigDecimal DEFAULT_MAINT_MARGIN_RATE = PerpMath.DEFAULT_MAINT_MARGIN_RATE;

    private Long id;
    private long accountId;
    private String symbol;
    private String side;
    private BigDecimal qty;
    private BigDecimal avgEntryPrice;
    private BigDecimal realizedPnl;
    /** 合约杠杆(PERP);SPOT null。 */
    private Integer leverage;
    /** 合约保证金模式 ISOLATED/CROSS(PERP);SPOT null。 */
    private MarginMode marginMode;
    /** 合约持仓方向 LONG/SHORT(PERP 双向);SPOT null。side 字符串 long/short/flat 保留兼容。 */
    private String positionSide;
    /** 强平价(逐仓简化公式);SPOT null。casUpdate 减仓不变。 */
    private BigDecimal liquidationPrice;
    /** 维持保证金;SPOT null。开仓算不变。 */
    private BigDecimal maintMargin;
    /** per-position 累积 initialMargin(SPOT=0/PERP);V32 frozen_amount 列 NOT NULL DEFAULT 0。逐仓强平判此列 + 派生 unrealizedPnl。字段默认 0(SPOT 持仓),PERP 开仓时由 applyPerpDelta 设 initialMargin 覆盖。 */
    private BigDecimal frozenAmount = BigDecimal.ZERO;
    /** 从 flat 打开仓的时刻(V59);PAPER 资金费 catch-up 结算下界。flat/SPOT/存量行为 null,开仓时由 applyPerpDelta 簿记,全平清空。 */
    private Instant openedAt;

    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public Position() {}

    /** 工厂方法：新建一个 flat 持仓（首次创建时用）。 */
    public static Position flat(long accountId, String symbol) {
        Position p = new Position();
        p.accountId = accountId;
        p.symbol = symbol;
        p.side = SIDE_FLAT;
        p.qty = BigDecimal.ZERO;
        p.avgEntryPrice = null;
        p.realizedPnl = BigDecimal.ZERO;
        p.version = 0;
        return p;
    }

    public boolean isFlat() {
        return SIDE_FLAT.equals(side) || qty == null || qty.signum() == 0;
    }

    // ---------- getters / setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public BigDecimal getAvgEntryPrice() {
        return avgEntryPrice;
    }

    public void setAvgEntryPrice(BigDecimal avgEntryPrice) {
        this.avgEntryPrice = avgEntryPrice;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public Integer getLeverage() {
        return leverage;
    }

    public void setLeverage(Integer leverage) {
        this.leverage = leverage;
    }

    public MarginMode getMarginMode() {
        return marginMode;
    }

    public void setMarginMode(MarginMode marginMode) {
        this.marginMode = marginMode;
    }

    public String getPositionSide() {
        return positionSide;
    }

    public void setPositionSide(String positionSide) {
        this.positionSide = positionSide;
    }

    public BigDecimal getLiquidationPrice() {
        return liquidationPrice;
    }

    public void setLiquidationPrice(BigDecimal liquidationPrice) {
        this.liquidationPrice = liquidationPrice;
    }

    public BigDecimal getMaintMargin() {
        return maintMargin;
    }

    public void setMaintMargin(BigDecimal maintMargin) {
        this.maintMargin = maintMargin;
    }

    public BigDecimal getFrozenAmount() {
        return frozenAmount;
    }

    public void setFrozenAmount(BigDecimal frozenAmount) {
        this.frozenAmount = frozenAmount;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    /** marketType 从 marginMode 派生:null→SPOT,ISOLATED/CROSS→PERP。Position 不存 marketType DB 列。 */
    public MarketType getMarketType() {
        return marginMode == null ? MarketType.SPOT : MarketType.PERP;
    }

    /**
     * 判定空头持仓(单向 {@code side="short"} 或双向 {@code positionSide="SHORT"})。
     * 派生方法共用,避免判定口径漂移。
     */
    private boolean isShortPosition() {
        return SIDE_SHORT.equalsIgnoreCase(side) || "SHORT".equalsIgnoreCase(positionSide);
    }

    /**
     * 派生未实现盈亏。
     *
     * <p>flat({@link #isFlat()} 返回 true)、markPrice 为 null、qty 为 null、avgEntryPrice 为 null
     * 时返回 {@code null}(调用方按"未知"处理)。否则:
     * <pre>
     *   diff = markPrice - avgEntryPrice
     *   LONG: unrealizedPnl = diff * qty
     *   SHORT: unrealizedPnl = -diff * qty
     * </pre>
     *
     * <p>"SHORT"判定:{@code side} 为 {@code "short"} 或 {@code positionSide} 为 {@code "SHORT"}(双向持仓)。
     * markPrice / marginBalance 不入 DB——仅运行时派生(撮合内核用)。
     *
     * @param markPrice 当前标记价(可空)
     * @return 未实现盈亏;flat 或字段缺失返回 null
     */
    public BigDecimal getUnrealizedPnl(BigDecimal markPrice) {
        if (isFlat() || markPrice == null || qty == null || avgEntryPrice == null) {
            return null;
        }
        BigDecimal diff = markPrice.subtract(avgEntryPrice);
        if (isShortPosition()) {
            diff = diff.negate();
        }
        return diff.multiply(qty);
    }

    /**
     * 派生保证金余额。
     *
     * <p>{@code marginBalance = frozenAmount + unrealizedPnl(markPrice)}。SPOT 场景
     * frozenAmount 为 0,unrealizedPnl 为 null 时退化为 0,故 SPOT flat 持仓返回 {@code 0}。
     * PERP 场景 frozenAmount = initialMargin(开仓时设入),markPrice 跌破维持保证金时
     * marginBalance 触发强平(见 {@code PaperExecutor})。
     *
     * <p><strong>CROSS 全仓</strong>:此方法是 per-position 视图(frozenAmount + unrealizedPnl),
     * 强平判定<strong>不用</strong>此方法。PaperExecutor.checkLiquidation CROSS 分支按 account 聚合所有
     * CROSS 仓算 marginBalance = paper_balance.free + SUM(unrealizedPnl),
     * marginRatio = SUM(maintMargin)/marginBalance(账户级)。
     *
     * <p>markPrice / marginBalance 不入 DB——仅运行时派生。
     *
     * @param markPrice 当前标记价(可空)
     * @return 保证金余额;never null(flat / 缺失场景返回 {@link BigDecimal#ZERO})
     */
    public BigDecimal getMarginBalance(BigDecimal markPrice) {
        BigDecimal unrealized = getUnrealizedPnl(markPrice);
        BigDecimal frozen = frozenAmount != null ? frozenAmount : BigDecimal.ZERO;
        return frozen.add(unrealized != null ? unrealized : BigDecimal.ZERO);
    }

    /**
     * 计算逐仓 margin-aware 简化强平价(展示/参考价)。
     *
     * <p>简化公式(与 OKX 实盘有偏差,PAPER 模拟):
     * <pre>
     *   mmr = maintMarginRate != null ? maintMarginRate : 0.005
     *   margin = frozenAmount != null ? frozenAmount : 0
     *   LONG:  liquidationPrice ≈ (avgEntryPrice × qty − margin) / (qty × (1 − mmr))
     *   SHORT: liquidationPrice ≈ (avgEntryPrice × qty + margin) / (qty × (1 + mmr))
     * </pre>
     *
     * <p>资金费侵蚀 frozenAmount 后本价随之移动(LONG 上移 / SHORT 下移);保证金耗尽时
     * 结果可 ≤0——因此强平<b>触发判定</b>不比较本价,走 marginBreached 谓词(marginBalance
     * vs maintMarginRequired,见 {@code PaperExecutor.checkLiquidation} ISOLATED 分支与
     * docs/perp-math-spec.md §3.6/§3.7)。本方法输出用于 positions.liquidation_price 展示列、
     * 事件 payload 与对账 fallback。
     *
     * <p>leverage 为 null(SPOT 行)、avgEntryPrice 为 null、qty 缺失或 ≤0(flat)时返回
     * {@code null}(margin-aware 公式分母含 qty)。公式与舍入点单源在
     * {@link PerpMath#liquidationPriceIsolated}(docs/perp-math-spec.md §3.6,双侧 fixtures 对拍)。
     *
     * <p>注:本方法为纯派生计算,不写回 {@code liquidationPrice} 字段;
     * 字段写入由开仓链路负责(OPEN 成交与资金费侵蚀后重算)。
     *
     * @param maintMarginRate 维持保证金率(可空,默认 0.005)
     * @return 强平价;SPOT / flat / 字段缺失返回 null
     */
    public BigDecimal computeLiquidationPrice(BigDecimal maintMarginRate) {
        if (marginMode == MarginMode.CROSS) {
            // CROSS 全仓无单仓强平价——强平由账户级 marginRatio 判定
            // (PaperExecutor.checkLiquidation CROSS 分支聚合所有 CROSS 仓算 marginBalance/maintMargin)
            return null;
        }
        // leverage null = SPOT 行(PERP 必设 leverage);qty ≤ 0 = flat(公式分母含 qty)
        if (leverage == null || leverage <= 0 || avgEntryPrice == null || qty == null || qty.signum() <= 0) {
            return null;
        }
        BigDecimal mmr = maintMarginRate != null ? maintMarginRate : DEFAULT_MAINT_MARGIN_RATE;
        BigDecimal margin = frozenAmount != null ? frozenAmount : BigDecimal.ZERO;
        // 非 SHORT 一律走多头公式(flat 不会到此分支,leverage 非空即 PERP 已开仓,此处为防御)
        return PerpMath.liquidationPriceIsolated(
                avgEntryPrice, qty, margin, mmr, isShortPosition() ? PerpMath.SIDE_SHORT : PerpMath.SIDE_LONG);
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
