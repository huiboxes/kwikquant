package com.kwikquant.trading.application;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.InsufficientBalanceException;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.RejectFillException;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.PositionMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 持仓服务。被 ExecutionService 在 §1.4 事务内调用。<strong>不开启自己的事务。</strong>
 *
 * <p>合约语义的开仓 / 加仓 / 减仓 / 平仓反手计算规则详见 wave4-tech-design §3.9。
 * 现货场景 side 始终 long 或 flat。
 *
 * <p>阶段2c 扩支持 PERP 合约持仓:SPOT 沿用 {@link #applyDelta} 反手分支(逐字保留),
 * PERP 走 {@link #applyPerpDelta} 按 {@link PositionEffect} 四向分桶,无反手。
 */
@Service
public class PositionService {

    /** 逐仓简化强平价计算默认维持保证金率(§3.2)。{@code recomputeAllLiquidationPrices} 可覆盖。 */
    private static final BigDecimal DEFAULT_MAINT_MARGIN_RATE = new BigDecimal("0.005");

    private final PositionMapper positionMapper;

    @Autowired
    public PositionService(PositionMapper positionMapper) {
        this.positionMapper = positionMapper;
    }

    /**
     * 应用一笔成交到持仓(SPOT 兼容入口)。委托 {@link #applyFill(long, String, OrderSide, BigDecimal, BigDecimal, BigDecimal, MarketType, PositionEffect, Integer, MarginMode)}
     * 传 SPOT/null/null/null/null,忽略返回的 realizedPnlDelta(SPOT 无逐仓平仓 PnL 概念,
     * PnL 在 {@code applyDelta} 内累计到 {@code realizedPnl} 字段)。
     *
     * <p>保留 6 参数重载避免破 SPOT 调用点(ExecutionService SPOT 链路、单元测试 mock 签名)。
     */
    public void applyFill(
            long accountId, String symbol, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal fee) {
        applyFill(accountId, symbol, side, qty, price, fee, MarketType.SPOT, null, null, null);
    }

    /**
     * 应用一笔成交到持仓(CAS 重试 {@value TradingConstants#MAX_CAS_RETRIES} 次,超限抛
     * {@link ConcurrencyConflictException} → 上游事务回滚)。
     *
     * <p>SPOT/null marketType:走 {@link #applyDelta}(反手分支逐字保留),返 {@link BigDecimal#ZERO}。
     *
     * <p>PERP marketType:按 {@link PositionEffect} 派生 positionSide 后用
     * {@link PositionMapper#findByAccountSymbolPosition} 查仓,无则内存构造 flat + leverage/marginMode +
     * {@link #applyPerpDelta} 后 insert(若 CLOSE_* on flat,applyPerpDelta 直接抛
     * {@link RejectFillException} 不持久化);有则 applyPerpDelta + casUpdate。返 realizedPnlDelta
     * (CLOSE_* 平仓 PnL;OPEN_* 返 ZERO),供 ExecutionService 2e 调
     * {@code balanceService.applyPnlSettlement} 把 PnL 入账。
     *
     * @param marketType     SPOT / PERP;null 按 SPOT 处理
     * @param positionEffect PERP 四向(OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT);SPOT 传 null
     * @param leverage       PERP 杠杆;SPOT 传 null
     * @param marginMode     PERP 保证金模式 ISOLATED/CROSS;SPOT 传 null
     * @return realizedPnlDelta(SPOT 返 ZERO;PERP CLOSE_* 返本次平仓 PnL,OPEN_* 返 ZERO)
     */
    public BigDecimal applyFill(
            long accountId,
            String symbol,
            OrderSide side,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal fee,
            MarketType marketType,
            PositionEffect positionEffect,
            Integer leverage,
            MarginMode marginMode) {
        boolean isPerp = marketType == MarketType.PERP;
        for (int attempt = 0; attempt < TradingConstants.MAX_CAS_RETRIES; attempt++) {
            if (isPerp) {
                String posSide = derivePositionSide(positionEffect);
                Position p = positionMapper.findByAccountSymbolPosition(accountId, symbol, posSide, marginMode);
                if (p == null) {
                    // 内存构造 flat + 合约字段,applyPerpDelta 填充后 insert。
                    // CLOSE_* on flat(flat.qty=0): applyPerpDelta 抛 RejectFillException,不进入 insert。
                    p = Position.flat(accountId, symbol);
                    p.setLeverage(leverage);
                    p.setMarginMode(marginMode);
                    BigDecimal realizedPnlDelta = applyPerpDelta(p, qty, price, positionEffect);
                    try {
                        positionMapper.insert(p);
                    } catch (org.springframework.dao.DuplicateKeyException ex) {
                        // 并发首次 insert 撞键 → 重试取已有
                        continue;
                    }
                    return realizedPnlDelta;
                }
                BigDecimal realizedPnlDelta = applyPerpDelta(p, qty, price, positionEffect);
                int affected = positionMapper.casUpdate(p);
                if (affected == 1) {
                    p.setVersion(p.getVersion() + 1);
                    return realizedPnlDelta;
                }
                // CAS 冲突,重试
            } else {
                Position p = positionMapper.findByAccountAndSymbol(accountId, symbol);
                if (p == null) {
                    p = newState(accountId, symbol, side, qty, price, fee);
                    try {
                        positionMapper.insert(p);
                        return BigDecimal.ZERO;
                    } catch (org.springframework.dao.DuplicateKeyException ex) {
                        // 并发首次 insert 撞键 → 重试取已有
                        continue;
                    }
                }
                applyDelta(p, side, qty, price, fee);
                int affected = positionMapper.casUpdate(p);
                if (affected == 1) {
                    p.setVersion(p.getVersion() + 1);
                    return BigDecimal.ZERO;
                }
                // CAS 冲突,重试
            }
        }
        throw new ConcurrencyConflictException("Position CAS failed after " + TradingConstants.MAX_CAS_RETRIES
                + " retries: account=" + accountId + " symbol=" + symbol);
    }

    /**
     * 从 {@link PositionEffect} 派生 positionSide 字符串(对齐 DB chk_positions_position_side 约束 'LONG'/'SHORT')。
     * OPEN_LONG/CLOSE_LONG → LONG,OPEN_SHORT/CLOSE_SHORT → SHORT。
     */
    private static String derivePositionSide(PositionEffect effect) {
        return (effect == PositionEffect.OPEN_LONG || effect == PositionEffect.CLOSE_LONG) ? "LONG" : "SHORT";
    }

    private Position newState(
            long accountId, String symbol, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal fee) {
        Position p = Position.flat(accountId, symbol);
        p.setSide(side == OrderSide.BUY ? Position.SIDE_LONG : Position.SIDE_SHORT);
        p.setQty(qty);
        p.setAvgEntryPrice(price);
        p.setRealizedPnl(fee.negate());
        return p;
    }

    /** 在已存在持仓上叠加一笔成交(SPOT)。修改 p 的字段,由调用方持久化。反手分支逐字保留。 */
    static void applyDelta(Position p, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal fee) {
        BigDecimal currentQty = p.getQty() == null ? BigDecimal.ZERO : p.getQty();
        String currentSide = p.getSide();
        BigDecimal currentAvg = p.getAvgEntryPrice();
        BigDecimal realizedPnl = p.getRealizedPnl() == null ? BigDecimal.ZERO : p.getRealizedPnl();

        boolean fillIsLong = side == OrderSide.BUY;
        boolean posIsLong = Position.SIDE_LONG.equals(currentSide) && currentQty.signum() > 0;
        boolean posIsShort = Position.SIDE_SHORT.equals(currentSide) && currentQty.signum() > 0;

        // case flat / 同向加仓
        if (currentQty.signum() == 0 || (posIsLong && fillIsLong) || (posIsShort && !fillIsLong)) {
            BigDecimal newQty = currentQty.add(qty);
            BigDecimal newAvg;
            if (currentQty.signum() == 0 || currentAvg == null) {
                newAvg = price;
            } else {
                BigDecimal totalCost = currentAvg.multiply(currentQty).add(price.multiply(qty));
                newAvg = totalCost.divide(newQty, 8, RoundingMode.HALF_UP);
            }
            p.setSide(fillIsLong ? Position.SIDE_LONG : Position.SIDE_SHORT);
            p.setQty(newQty);
            p.setAvgEntryPrice(newAvg);
            p.setRealizedPnl(realizedPnl.subtract(fee));
            return;
        }

        // 反向减仓 / 平仓 / 平仓反手
        BigDecimal closeQty = qty.min(currentQty);
        BigDecimal directionalPnl = posIsLong
                ? price.subtract(currentAvg).multiply(closeQty)
                : currentAvg.subtract(price).multiply(closeQty);
        BigDecimal newRealizedPnl = realizedPnl.add(directionalPnl).subtract(fee);

        BigDecimal remainQty = qty.subtract(closeQty);
        BigDecimal afterCloseQty = currentQty.subtract(closeQty);

        if (remainQty.signum() == 0) {
            // 单纯减仓 / 平仓
            if (afterCloseQty.signum() == 0) {
                p.setSide(Position.SIDE_FLAT);
                p.setQty(BigDecimal.ZERO);
                p.setAvgEntryPrice(null);
            } else {
                p.setQty(afterCloseQty);
            }
        } else {
            // 平仓反手：撇清原有，剩余按新价开仓
            p.setSide(fillIsLong ? Position.SIDE_LONG : Position.SIDE_SHORT);
            p.setQty(remainQty);
            p.setAvgEntryPrice(price);
        }
        p.setRealizedPnl(newRealizedPnl);
    }

    /**
     * 在已存在 PERP 持仓上叠加一笔成交(按 {@link PositionEffect} 四向分桶,无反手)。
     * 修改 p 的字段,由调用方持久化。
     *
     * <p><b>OPEN_LONG / OPEN_SHORT</b>:
     * <ul>
     *   <li>旧仓非空(qty &gt; 0,加仓): qty += fillQty; avgEntryPrice = 加权平均
     *       ((oldAvg×oldQty + fillPrice×fillQty) / newQty) setScale(8, HALF_UP);
     *       frozenAmount += fillPrice×fillQty/leverage (initialMargin 增量,逐仓 §3.1)</li>
     *   <li>旧仓空(qty == 0,flat,新仓): qty = fillQty; avgEntryPrice = fillPrice;
     *       frozenAmount = fillPrice×fillQty/leverage</li>
     *   <li>side = long/short; positionSide = LONG/SHORT;
     *       liquidationPrice = {@link Position#computeLiquidationPrice}(DEFAULT_MAINT_MARGIN_RATE)</li>
     *   <li>返 {@link BigDecimal#ZERO}(开仓无已实现 PnL)</li>
     * </ul>
     *
     * <p><b>CLOSE_LONG / CLOSE_SHORT</b>:
     * <ul>
     *   <li>fillQty &gt; position.qty → 抛 {@link RejectFillException}(B2-s ③,§13 拍板 4 优先抛非 cap)</li>
     *   <li>realizedPnlDelta: CLOSE_LONG = (fillPrice - avgEntryPrice) × fillQty;
     *       CLOSE_SHORT = (avgEntryPrice - fillPrice) × fillQty</li>
     *   <li>qty -= fillQty; frozenAmount 按比例释放 (frozenAmount × fillQty/oldQty) setScale(8, HALF_UP);
     *       realizedPnl += realizedPnlDelta</li>
     *   <li>qty == 0(全平): side=flat, avgEntryPrice=null, liquidationPrice=null,
     *       frozenAmount=ZERO, positionSide=null</li>
     *   <li>qty &gt; 0(部分平仓): avgEntryPrice 不变, frozenAmount 扣减, side/positionSide 不变</li>
     *   <li>返 realizedPnlDelta(供 ExecutionService 2e 调 balanceService.applyPnlSettlement)</li>
     * </ul>
     *
     * @param p      持仓(leverage 必须已设,PERP 场景)
     * @param fillQty 本次成交数量
     * @param fillPrice 本次成交价
     * @param effect  四向 positionEffect
     * @return realizedPnlDelta(OPEN_* 返 ZERO;CLOSE_* 返本次平仓 PnL)
     * @throws RejectFillException CLOSE_* 时 fillQty 超过持仓 qty
     */
    static BigDecimal applyPerpDelta(Position p, BigDecimal fillQty, BigDecimal fillPrice, PositionEffect effect) {
        BigDecimal currentQty = p.getQty() == null ? BigDecimal.ZERO : p.getQty();
        BigDecimal currentAvg = p.getAvgEntryPrice();
        BigDecimal currentFrozen = p.getFrozenAmount() == null ? BigDecimal.ZERO : p.getFrozenAmount();
        BigDecimal currentRealized = p.getRealizedPnl() == null ? BigDecimal.ZERO : p.getRealizedPnl();

        if (effect == PositionEffect.OPEN_LONG || effect == PositionEffect.OPEN_SHORT) {
            // 先设 side/positionSide,后续 computeLiquidationPrice 依赖 isShortPosition 判定
            if (effect == PositionEffect.OPEN_LONG) {
                p.setSide(Position.SIDE_LONG);
                p.setPositionSide("LONG");
            } else {
                p.setSide(Position.SIDE_SHORT);
                p.setPositionSide("SHORT");
            }
            BigDecimal leverage = new BigDecimal(p.getLeverage());
            BigDecimal initialMarginDelta = fillPrice.multiply(fillQty).divide(leverage, 8, RoundingMode.HALF_UP);
            if (currentQty.signum() > 0) {
                // 加仓
                BigDecimal newQty = currentQty.add(fillQty);
                BigDecimal totalCost = currentAvg.multiply(currentQty).add(fillPrice.multiply(fillQty));
                BigDecimal newAvg = totalCost.divide(newQty, 8, RoundingMode.HALF_UP);
                p.setQty(newQty);
                p.setAvgEntryPrice(newAvg);
                p.setFrozenAmount(currentFrozen.add(initialMarginDelta));
            } else {
                // 新仓(flat)
                p.setQty(fillQty);
                p.setAvgEntryPrice(fillPrice);
                p.setFrozenAmount(currentFrozen.add(initialMarginDelta));
            }
            p.setLiquidationPrice(p.computeLiquidationPrice(DEFAULT_MAINT_MARGIN_RATE));
            return BigDecimal.ZERO;
        }

        // CLOSE_LONG / CLOSE_SHORT
        if (fillQty.compareTo(currentQty) > 0) {
            throw new RejectFillException("PERP CLOSE over-position: fillQty=" + fillQty + " > qty=" + currentQty);
        }
        BigDecimal realizedPnlDelta;
        if (effect == PositionEffect.CLOSE_LONG) {
            realizedPnlDelta = fillPrice.subtract(currentAvg).multiply(fillQty);
        } else {
            realizedPnlDelta = currentAvg.subtract(fillPrice).multiply(fillQty);
        }
        BigDecimal newQty = currentQty.subtract(fillQty);
        BigDecimal frozenRelease = currentQty.signum() > 0
                ? currentFrozen.multiply(fillQty).divide(currentQty, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal newFrozen = newQty.signum() == 0 ? BigDecimal.ZERO : currentFrozen.subtract(frozenRelease);
        p.setQty(newQty);
        p.setRealizedPnl(currentRealized.add(realizedPnlDelta));
        p.setFrozenAmount(newFrozen);
        if (newQty.signum() == 0) {
            // 全平:清掉所有方向性字段
            p.setSide(Position.SIDE_FLAT);
            p.setAvgEntryPrice(null);
            p.setLiquidationPrice(null);
            p.setPositionSide(null);
        }
        // 部分平仓:avgEntryPrice/side/positionSide 不变
        return realizedPnlDelta;
    }

    /**
     * 全量重算所有 PERP 持仓的 liquidationPrice。
     *
     * <p><b>用途</b>:维持保证金率(maintMarginRate)配置改后,启动 / 触发全量重算。配置改动需要重启
     * (m1-s:不可热改),本方法用于<b>重启后</b>对历史持仓批量刷新 liquidationPrice,使新配置生效。
     * 运行期不会自动调用,需运维 / 启动钩子显式触发。
     *
     * <p>遍历所有 margin_mode IN ('ISOLATED','CROSS') 的持仓,position.setLiquidationPrice
     * (position.computeLiquidationPrice(mmr)),casUpdate 持久化。CAS 失败(并发改)则跳过该行
     * 并 warn(本方法为批量管理操作,不与交易链路竞争重试,失败留待下一轮重算)。
     *
     * @param maintMarginRate 维持保证金率;null 走 {@link Position#computeLiquidationPrice} 默认 0.005
     */
    public void recomputeAllLiquidationPrices(BigDecimal maintMarginRate) {
        List<Position> perpPositions = positionMapper.findAllPerpPositions();
        for (Position p : perpPositions) {
            BigDecimal newLiq = p.computeLiquidationPrice(maintMarginRate);
            p.setLiquidationPrice(newLiq);
            int affected = positionMapper.casUpdate(p);
            if (affected == 1) {
                p.setVersion(p.getVersion() + 1);
            }
            // CAS 失败:并发改,跳过留待下一轮重算(批量管理操作,不与交易链路竞争)
        }
    }

    /** 仅做粗略保证金校验（现货场景）。Wave 5 RiskGate 完整覆盖。 */
    @SuppressWarnings("unused")
    void requireBalance(BigDecimal required, BigDecimal available) {
        if (available == null || available.compareTo(required) < 0) {
            throw new InsufficientBalanceException(
                    "insufficient balance: required=" + required + " available=" + available);
        }
    }

    public List<Position> findByAccount(long accountId) {
        return positionMapper.findByAccount(accountId);
    }

    public Position findByAccountAndSymbol(long accountId, String symbol) {
        return positionMapper.findByAccountAndSymbol(accountId, symbol);
    }

    public Position findById(long id) {
        return positionMapper.findById(id);
    }
}
