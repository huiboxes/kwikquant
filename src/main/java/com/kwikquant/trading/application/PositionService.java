package com.kwikquant.trading.application;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PerpMath;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
import com.kwikquant.trading.domain.RejectFillException;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.PositionMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 持仓服务。被 ExecutionService 在成交入账事务内调用。<strong>不开启自己的事务。</strong>
 *
 * <p>合约语义的开仓/加仓/减仓/平仓反手计算规则见 {@link #applyDelta}(SPOT)与 {@link #applyPerpDelta}(PERP)实现。
 * 现货场景 side 始终 long 或 flat。
 *
 * <p>PERP 合约持仓:SPOT 沿用 {@link #applyDelta} 反手分支(逐字保留),
 * PERP 走 {@link #applyPerpDelta} 按 {@link PositionEffect} 四向分桶,无反手。
 */
@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);

    private final PositionMapper positionMapper;

    @Autowired
    public PositionService(PositionMapper positionMapper) {
        this.positionMapper = positionMapper;
    }

    /**
     * PERP 成交应用到持仓的结果。{@code realizedPnlDelta} 是本次平仓方向性毛 PnL(OPEN = ZERO);
     * {@code marginDelta} 是内核算出的仓位保证金变动(OPEN = +initialMargin 增量,CLOSE = −释放量,
     * docs/perp-math-spec.md §3.12)——余额侧(PaperBalanceAdapter)与强平释放锁定保证金都消费此值,
     * 保证 positions.frozen_amount 与 paper_balances.used 两本账逐笔一致。SPOT 场景 marginDelta 恒 ZERO。
     */
    public record PerpFillOutcome(BigDecimal realizedPnlDelta, BigDecimal marginDelta) {}

    /**
     * 应用一笔成交到持仓(SPOT 兼容入口)。委托 {@link #applyFill(long, String, OrderSide, BigDecimal, BigDecimal, BigDecimal, MarketType, PositionEffect, Integer, MarginMode)}
     * 传 SPOT/null/null/null/null,返回平仓 PnL(开仓/加仓 = ZERO;反向减仓/平仓 = 本次平仓 PnL)。
     *
     * <p>保留 6 参数重载避免破 SPOT 调用点(ExecutionService SPOT 链路、单元测试 mock 签名)。
     * 返 {@code BigDecimal}(从 void 改):让 ExecutionService 回填 fills.realized_pnl_delta,
     * 供 DAILY_LOSS_LIMIT 按日汇总真实已实现 PnL(旧口径把开仓 BUY 支出当亏损误拦)。
     */
    public BigDecimal applyFill(
            long accountId, String symbol, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal fee) {
        return applyFill(accountId, symbol, side, qty, price, fee, MarketType.SPOT, null, null, null)
                .realizedPnlDelta();
    }

    /**
     * 按 positionSide 找本地 PERP 持仓(实盘强平/ADL 同步用)。
     *
     * <p>{@link #findAllByAccountAndSymbol} 返同 account+symbol 所有持仓(SPOT + PERP 双向),本方法
     * 过滤掉 SPOT 行(marginMode null)+ 按 positionSide 匹配,返唯一 PERP 持仓(V38 索引保证
     * (account,symbol,positionSide,marginMode,leverage) 唯一,但本方法不区分 marginMode/leverage,
     * 返第一个匹配 positionSide 的 PERP 行——同 symbol 同 posSide 不同 marginMode/leverage 的多行
     * 实际很少,且都需强平,取第一个够用)。
     *
     * @param positionSide LONG/SHORT;null(net 模式)返第一个 PERP 行
     * @return 匹配的 PERP 持仓;无则 null(已 flat 或无持仓)
     */
    public Position findPerpPositionBySide(long accountId, String symbol, PositionSide positionSide) {
        List<Position> all = positionMapper.findAllByAccountAndSymbol(accountId, symbol);
        String target = positionSide == null ? null : positionSide.name();
        for (Position p : all) {
            if (p.getMarginMode() == null) {
                continue; // SPOT 行跳过(margin_mode NULL)
            }
            if (p.isFlat()) {
                continue; // flat 桶行保留 positionSide(唯一索引桶身份),但不是可结算持仓
            }
            if (target == null) {
                return p; // net 模式不区分 posSide,返第一个 PERP
            }
            if (target.equals(p.getPositionSide())) {
                return p;
            }
        }
        return null;
    }

    /**
     * 应用一笔成交到持仓(CAS 重试 {@value TradingConstants#MAX_CAS_RETRIES} 次,超限抛
     * {@link ConcurrencyConflictException} → 上游事务回滚)。
     *
     * <p>SPOT/null marketType:走 {@link #applyDelta}(反手分支逐字保留),返本次平仓 PnL(开仓/加仓 = ZERO;
     * 反向减仓/平仓/平仓反手 = directionalPnl),供 ExecutionService 回填 fills.realized_pnl_delta
     * (marginDelta 恒 ZERO)。
     *
     * <p>PERP marketType:按 {@link PositionEffect} 派生 positionSide 后用
     * {@link PositionMapper#findByAccountSymbolPosition} 查仓,无则内存构造 flat + leverage/marginMode +
     * {@link #applyPerpDelta} 后 insert(若 CLOSE_* on flat,applyPerpDelta 直接抛
     * {@link RejectFillException} 不持久化);有则 applyPerpDelta + casUpdate。返
     * {@link PerpFillOutcome}:realizedPnlDelta 供 ExecutionService 调
     * {@code balanceService.applyPnlSettlement} 入账,marginDelta 供余额侧锁定/释放 ISOLATED
     * 仓位保证金(FillCommand 透传)与强平释放(LiquidationService)。
     *
     * @param marketType     SPOT / PERP;null 按 SPOT 处理
     * @param positionEffect PERP 四向(OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT);SPOT 传 null
     * @param leverage       PERP 杠杆;SPOT 传 null
     * @param marginMode     PERP 保证金模式 ISOLATED/CROSS;SPOT 传 null
     * @return 方向性毛 realizedPnlDelta + 仓位保证金变动 marginDelta；费用另计入
     *         Position.realizedPnl，供余额结算避免双扣
     */
    public PerpFillOutcome applyFill(
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
                String posSide = positionEffect.toPositionSide();
                Position p =
                        positionMapper.findByAccountSymbolPosition(accountId, symbol, posSide, marginMode, leverage);
                if (p == null) {
                    // 内存构造 flat + 合约字段,applyPerpDelta 填充后 insert。
                    // CLOSE_* on flat(flat.qty=0): applyPerpDelta 抛 RejectFillException,不进入 insert。
                    p = Position.flat(accountId, symbol);
                    p.setLeverage(leverage);
                    p.setMarginMode(marginMode);
                    PerpFillOutcome outcome = applyPerpDelta(p, qty, price, positionEffect);
                    applySignedFeeCost(p, fee);
                    try {
                        positionMapper.insert(p);
                    } catch (org.springframework.dao.DuplicateKeyException ex) {
                        // 并发首次 insert 撞键 → 重试取已有
                        continue;
                    }
                    return outcome;
                }
                PerpFillOutcome outcome = applyPerpDelta(p, qty, price, positionEffect);
                applySignedFeeCost(p, fee);
                int affected = positionMapper.casUpdate(p);
                if (affected == 1) {
                    p.setVersion(p.getVersion() + 1);
                    return outcome;
                }
                // CAS 冲突,重试
            } else {
                Position p = positionMapper.findByAccountAndSymbol(accountId, symbol);
                if (p == null) {
                    p = newState(accountId, symbol, side, qty, price, fee);
                    try {
                        positionMapper.insert(p);
                        return new PerpFillOutcome(BigDecimal.ZERO, BigDecimal.ZERO);
                    } catch (org.springframework.dao.DuplicateKeyException ex) {
                        // 并发首次 insert 撞键 → 重试取已有
                        continue;
                    }
                }
                BigDecimal realizedPnlDelta = applyDelta(p, side, qty, price, fee);
                int affected = positionMapper.casUpdate(p);
                if (affected == 1) {
                    p.setVersion(p.getVersion() + 1);
                    return new PerpFillOutcome(realizedPnlDelta, BigDecimal.ZERO);
                }
                // CAS 冲突,重试
            }
        }
        throw new ConcurrencyConflictException("Position CAS failed after " + TradingConstants.MAX_CAS_RETRIES
                + " retries: account=" + accountId + " symbol=" + symbol);
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

    private static void applySignedFeeCost(Position position, BigDecimal fee) {
        BigDecimal realizedPnl = position.getRealizedPnl() == null ? BigDecimal.ZERO : position.getRealizedPnl();
        BigDecimal feeCost = fee == null ? BigDecimal.ZERO : fee;
        position.setRealizedPnl(realizedPnl.subtract(feeCost));
    }

    /**
     * 在已存在持仓上叠加一笔成交(SPOT)。修改 p 的字段,由调用方持久化。反手分支逐字保留。
     *
     * @return 本次 fill 的已实现盈亏增量(开仓/加仓 = ZERO;反向减仓/平仓/平仓反手 = 本次平仓部分的
     *         平仓 PnL,directionalPnl),供 ExecutionService 回填 fills.realized_pnl_delta。
     */
    static BigDecimal applyDelta(
            Position p, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal signedFeeCost) {
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
            p.setRealizedPnl(realizedPnl.subtract(signedFeeCost));
            return BigDecimal.ZERO;
        }

        // 反向减仓 / 平仓 / 平仓反手
        BigDecimal closeQty = qty.min(currentQty);
        BigDecimal directionalPnl = posIsLong
                ? price.subtract(currentAvg).multiply(closeQty)
                : currentAvg.subtract(price).multiply(closeQty);
        BigDecimal newRealizedPnl = realizedPnl.add(directionalPnl).subtract(signedFeeCost);

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
        return directionalPnl;
    }

    /**
     * 在已存在 PERP 持仓上叠加一笔成交(按 {@link PositionEffect} 四向分桶,无反手)。
     * 修改 p 的字段,由调用方持久化。
     *
     * <p><b>OPEN_LONG / OPEN_SHORT</b>:
     * <ul>
     *   <li>旧仓非空(qty &gt; 0,加仓): qty += fillQty; avgEntryPrice = 加权平均
     *       ((oldAvg×oldQty + fillPrice×fillQty) / newQty) setScale(8, HALF_UP);
     *       frozenAmount += fillPrice×fillQty/leverage (initialMargin 增量,逐仓)</li>
     *   <li>旧仓空(qty == 0,flat,新仓): qty = fillQty; avgEntryPrice = fillPrice;
     *       frozenAmount = fillPrice×fillQty/leverage</li>
     *   <li>side = long/short; positionSide = LONG/SHORT;
     *       liquidationPrice = {@link Position#computeLiquidationPrice}(DEFAULT_MAINT_MARGIN_RATE)</li>
     *   <li>返 {@link BigDecimal#ZERO}(开仓无已实现 PnL)</li>
     * </ul>
     *
     * <p><b>CLOSE_LONG / CLOSE_SHORT</b>:
     * <ul>
     *   <li>fillQty &gt; position.qty → 抛 {@link RejectFillException}(优先抛非 cap)</li>
     *   <li>realizedPnlDelta: CLOSE_LONG = (fillPrice - avgEntryPrice) × fillQty;
     *       CLOSE_SHORT = (avgEntryPrice - fillPrice) × fillQty</li>
     *   <li>qty -= fillQty; frozenAmount 按比例释放(全平精确释放,免除法 dust);
     *       realizedPnl += realizedPnlDelta</li>
     *   <li>qty == 0(全平): side=flat, avgEntryPrice=null, liquidationPrice=null,
     *       frozenAmount=0;positionSide 保留(双向持仓桶身份,V38 唯一索引键不折叠)</li>
     *   <li>frozenAmount &lt; 0(资金费穿蚀仓): 内核拒负保证金,旁路只放行全平(毛 PnL 走
     *       {@link PerpMath#closedPnl},frozen 清零),部分平仓/加仓 RejectFillException</li>
     *   <li>qty &gt; 0(部分平仓): avgEntryPrice 不变, frozenAmount 扣减, side/positionSide 不变</li>
     *   <li>返 realizedPnlDelta(供 ExecutionService 2e 调 balanceService.applyPnlSettlement)</li>
     * </ul>
     *
     * <p>数值语义(加权均价/初始保证金/平仓 PnL/保证金释放,含舍入点)单源在
     * {@link PerpMath#applyPositionDelta}(docs/perp-math-spec.md §3.12,双侧 fixtures 对拍);
     * 本方法只做四向 effect → (positionSide, open) 映射与 Position 字段簿记。
     *
     * @param p      持仓(leverage 必须已设,PERP 场景)
     * @param fillQty 本次成交数量(币数量)
     * @param fillPrice 本次成交价
     * @param effect  四向 positionEffect
     * @return {@link PerpFillOutcome}(realizedPnlDelta:OPEN_* 返 ZERO、CLOSE_* 返本次平仓 PnL;
     *         marginDelta:OPEN_* 返 +initialMargin 增量、CLOSE_* 返 −释放量)
     * @throws RejectFillException CLOSE_* 时 fillQty 超过持仓 qty
     */
    static PerpFillOutcome applyPerpDelta(Position p, BigDecimal fillQty, BigDecimal fillPrice, PositionEffect effect) {
        boolean open = effect == PositionEffect.OPEN_LONG || effect == PositionEffect.OPEN_SHORT;
        String posSide = effect.toPositionSide();
        BigDecimal currentQty = p.getQty() == null ? BigDecimal.ZERO : p.getQty();
        BigDecimal currentFrozen = p.getFrozenAmount() == null ? BigDecimal.ZERO : p.getFrozenAmount();
        BigDecimal currentRealized = p.getRealizedPnl() == null ? BigDecimal.ZERO : p.getRealizedPnl();

        if (!open && fillQty.compareTo(currentQty) > 0) {
            // 域层异常语义保留(RejectFillException 由入账事务上游处理);内核 OVER_CLOSE 只是防御
            throw new RejectFillException("PERP CLOSE over-position: fillQty=" + fillQty + " > qty=" + currentQty);
        }
        if (currentFrozen.signum() < 0) {
            // 穿蚀仓(资金费把 ISOLATED 保证金侵蚀为负):内核 applyPositionDelta 前置校验
            // requireNonNegative(currentFrozenMargin) 会拒——与回测侧同构旁路(perp_ledger
            // "强平记账不走内核 CLOSE 段",docs/perp-backtest-spec.md §3.3 穿蚀仓)。
            // 只放行全平(强平 processLiquidation 与手动全平 CLOSE_*):毛 PnL 走内核 closedPnl,
            // frozen 清零(marginDelta=−frozen>0;余额侧负释放额由 LiquidationService/applyPerpFill
            // 的 used→free 反向划转吸收缺口,total 守恒)。部分平仓与加仓拒——穿蚀仓无"按比例释放"
            // 语义,只能整体退出(与回测 MARGIN_DEPLETED 主动单全拒同一保守近似)。
            if (open || fillQty.compareTo(currentQty) < 0) {
                throw new RejectFillException("PERP order rejected: position margin depleted (frozenAmount="
                        + currentFrozen.toPlainString() + "), only full close is allowed");
            }
            BigDecimal depletedPnl;
            try {
                depletedPnl = PerpMath.closedPnl(posSide, p.getAvgEntryPrice(), fillPrice, fillQty);
            } catch (IllegalArgumentException e) {
                throw new RejectFillException("PERP fill rejected by math kernel: " + e.getMessage(), e);
            }
            BigDecimal depletedMarginDelta = currentFrozen.negate();
            p.setQty(BigDecimal.ZERO);
            p.setAvgEntryPrice(null);
            p.setFrozenAmount(BigDecimal.ZERO);
            p.setRealizedPnl(currentRealized.add(depletedPnl));
            p.setSide(Position.SIDE_FLAT);
            p.setLiquidationPrice(null);
            p.setMaintMargin(null);
            p.setOpenedAt(null);
            // positionSide 保留(桶身份,与下方内核全平路径同一纪律)
            return new PerpFillOutcome(depletedPnl, depletedMarginDelta);
        }
        PerpMath.PositionDelta delta;
        try {
            delta = PerpMath.applyPositionDelta(
                    posSide,
                    open,
                    currentQty,
                    p.getAvgEntryPrice(),
                    currentFrozen,
                    fillQty,
                    fillPrice,
                    p.getLeverage());
        } catch (IllegalArgumentException e) {
            // 内核校验失败 = 本笔成交不可应用(合法路径已被上游 guard 挡死,走到这只可能是脏数据):
            // 转 RejectFillException 让订单进 REJECTED 终态,避免落通用重试路径每 tick 重抛
            throw new RejectFillException("PERP fill rejected by math kernel: " + e.getMessage(), e);
        }

        p.setQty(delta.newQty());
        p.setAvgEntryPrice(delta.newAvgEntryPrice());
        p.setFrozenAmount(currentFrozen.add(delta.marginDelta()));
        if (open) {
            // 先设 side/positionSide,后续 computeLiquidationPrice 依赖 isShortPosition 判定
            p.setSide(PerpMath.SIDE_LONG.equals(posSide) ? Position.SIDE_LONG : Position.SIDE_SHORT);
            p.setPositionSide(posSide);
            p.setLiquidationPrice(p.computeLiquidationPrice(Position.DEFAULT_MAINT_MARGIN_RATE));
            // V31 maint_margin 列的簿记兑现(此前从未写入,DTO 恒 null):按开仓均价口径的维持
            // 保证金参考额,与 liquidationPrice 同为参考价(强平判定实时用 marginBreached 谓词)
            p.setMaintMargin(PerpMath.maintenanceMarginRequired(
                    delta.newAvgEntryPrice(), delta.newQty(), Position.DEFAULT_MAINT_MARGIN_RATE));
            if (currentQty.signum() == 0) {
                // flat→open:簿记开仓时刻(V59 opened_at,资金费 catch-up 结算下界)。加仓不动
                // (保留首开时刻)。用事务内墙钟近似成交时刻——catch-up 下界只要求期次网格级精度
                p.setOpenedAt(java.time.Instant.now());
            }
            return new PerpFillOutcome(BigDecimal.ZERO, delta.marginDelta());
        }
        p.setRealizedPnl(currentRealized.add(delta.realizedPnlDelta()));
        if (delta.newQty().signum() > 0 && !open) {
            // 部分平仓:maint_margin 参考额按新 qty 重算(否则 DTO/CLI 透出开仓口径的陈旧值;
            // 强平判定实时用 marginBreached 谓词,不受此列影响)
            p.setMaintMargin(PerpMath.maintenanceMarginRequired(
                    delta.newAvgEntryPrice(), delta.newQty(), Position.DEFAULT_MAINT_MARGIN_RATE));
        }
        if (delta.newQty().signum() == 0) {
            // 全平:清掉方向性状态字段(avgEntryPrice 已由 delta 置 null)。**positionSide 保留**——
            // 它是双向持仓的桶身份:V38 唯一索引按 COALESCE(position_side,'LONG') 折叠,置 null 会把
            // flat SHORT 行折叠成 'LONG' 键撞上既有 flat LONG 行(casUpdate 抛无捕获的
            // DuplicateKeyException → 强平/平仓事务回滚死循环)。保留后 flat 行键不冲突,重开同向
            // 仓位经 findByAccountSymbolPosition 原行复用(LONG 存量 null 行仍由 COALESCE 命中)。
            p.setSide(Position.SIDE_FLAT);
            p.setLiquidationPrice(null);
            p.setMaintMargin(null);
            p.setOpenedAt(null); // 重开时重新簿记(防 flat 期间历史期次被 catch-up 错误回收)
        }
        // 部分平仓:avgEntryPrice 原样写回,side/positionSide 不变
        return new PerpFillOutcome(delta.realizedPnlDelta(), delta.marginDelta());
    }

    /**
     * ISOLATED 仓位保证金的资金费侵蚀/增厚(OKX 逐仓语义:资金费直接收付仓位保证金,
     * 不动账户可用余额)。frozenAmount += fundingAmount(可负——资金费可把保证金侵蚀穿仓,
     * positions.frozen_amount 无 CHECK 约束),liquidationPrice 按新保证金重算(margin-aware,
     * 侵蚀后 LONG 强平价上移/穿仓后 ≤0,PaperExecutor 的 marginBreached 判定随之触发)。
     *
     * <p>CAS 重试 {@value TradingConstants#MAX_CAS_RETRIES} 次,超限抛
     * {@link ConcurrencyConflictException}(上游结算事务回滚,期次幂等键保证下一轮重结不双扣)。
     *
     * @param positionId    持仓 ID
     * @param fundingAmount 本期资金费(已带符号:正=收/增厚,负=付/侵蚀)
     * @param fundingTime   本期期次时刻(世代守卫:晚于当前仓位 openedAt 的期次才侵蚀仓位保证金)
     * @return true=已侵蚀仓位保证金;false=持仓不存在或已 flat(保证金已随平仓释放回账户,
     *         调用方降级为账户现金落账)
     */
    public boolean applyFundingErosion(long positionId, BigDecimal fundingAmount, java.time.Instant fundingTime) {
        for (int attempt = 0; attempt < TradingConstants.MAX_CAS_RETRIES; attempt++) {
            Position p = positionMapper.findById(positionId);
            if (p == null || p.isFlat()) {
                return false;
            }
            if (fundingTime != null
                    && p.getOpenedAt() != null
                    && p.getOpenedAt().isAfter(fundingTime)) {
                // 世代守卫:该期次早于当前仓位实例的开仓时刻——pass 在途时全平重开(桶行复用),
                // 用旧实例快照算的期次金额打进新实例会错额侵蚀保证金(甚至提前触发强平)。
                // 返 false 降级现金口径落账(scheduler 的 openedAt floor 只保护后续 pass,不保护在途 pass)。
                return false;
            }
            BigDecimal frozen = p.getFrozenAmount() == null ? BigDecimal.ZERO : p.getFrozenAmount();
            p.setFrozenAmount(frozen.add(fundingAmount));
            p.setLiquidationPrice(p.computeLiquidationPrice(Position.DEFAULT_MAINT_MARGIN_RATE));
            int affected = positionMapper.casUpdate(p);
            if (affected == 1) {
                p.setVersion(p.getVersion() + 1);
                return true;
            }
            // CAS 冲突:平仓/成交事务先行,重读再侵蚀(读到的 frozen 已含对方变动)
        }
        throw new ConcurrencyConflictException("Position funding erosion CAS failed after "
                + TradingConstants.MAX_CAS_RETRIES + " retries: positionId=" + positionId);
    }

    /**
     * 全量重算所有 PERP 持仓的 liquidationPrice。
     *
     * <p><b>用途</b>:维持保证金率(maintMarginRate)配置改后,启动 / 触发全量重算。配置改动需要重启
     * (不可热改),本方法用于<b>重启后</b>对历史持仓批量刷新 liquidationPrice,使新配置生效。
     * 运行期不会自动调用,需运维 / 启动钩子显式触发。
     *
     * <p>遍历所有 margin_mode IN ('ISOLATED','CROSS') 的持仓,position.setLiquidationPrice
     * (position.computeLiquidationPrice(mmr)),casUpdate 持久化。CAS 失败(并发改)或行数据
     * 脏(内核校验拒,如 avgEntryPrice ≤ 0)则跳过该行并 warn(本方法为批量管理操作,不与交易
     * 链路竞争重试,失败留待下一轮重算;单行失败不中断批量——部分应用比跳行更糟)。
     *
     * @param maintMarginRate 维持保证金率;null 走 {@link Position#computeLiquidationPrice} 默认 0.005
     */
    public void recomputeAllLiquidationPrices(BigDecimal maintMarginRate) {
        List<Position> perpPositions = positionMapper.findAllPerpPositions();
        for (Position p : perpPositions) {
            try {
                BigDecimal newLiq = p.computeLiquidationPrice(maintMarginRate);
                p.setLiquidationPrice(newLiq);
                int affected = positionMapper.casUpdate(p);
                if (affected == 1) {
                    p.setVersion(p.getVersion() + 1);
                }
                // CAS 失败:并发改,跳过留待下一轮重算(批量管理操作,不与交易链路竞争)
            } catch (RuntimeException e) {
                log.warn(
                        "[position] recompute liquidationPrice skipped row: positionId={} error={}",
                        p.getId(),
                        e.getMessage());
            }
        }
    }

    public List<Position> findByAccount(long accountId) {
        return positionMapper.findByAccount(accountId);
    }

    public Position findByAccountAndSymbol(long accountId, String symbol) {
        return positionMapper.findByAccountAndSymbol(accountId, symbol);
    }

    /** 查某账户某 symbol 所有持仓(含 SPOT + PERP 双向,返 List)。供 GET /positions?symbol= 用。 */
    public List<Position> findAllByAccountAndSymbol(long accountId, String symbol) {
        return positionMapper.findAllByAccountAndSymbol(accountId, symbol);
    }

    public Position findById(long id) {
        return positionMapper.findById(id);
    }

    /**
     * 跨账户查某 symbol 某交易所的所有模拟盘 PERP 持仓(强平判定用)。
     *
     * <p>委托 {@link PositionMapper#findAllPerpBySymbolAndExchange},JOIN exchange_accounts 过滤
     * paper_trading + exchange。PaperExecutor.onTicker 开头遍历返回的持仓判强平(markPrice 跌破
     * liquidationPrice),触发则调 {@code ExecutionService.processLiquidation}。
     *
     * @param symbol   交易对(BTC/USDT)
     * @param exchange ticker 来源交易所(只强平该交易所账户的持仓,避免串价)
     * @return 该 symbol 该 exchange 的所有模拟盘 PERP 持仓(无则空 List)
     */
    public List<Position> findPerpForLiquidation(String symbol, com.kwikquant.shared.types.Exchange exchange) {
        return positionMapper.findAllPerpBySymbolAndExchange(symbol, exchange);
    }

    /**
     * 查某账户所有 CROSS 全仓 PERP 持仓(跨 symbol)。PaperExecutor.checkLiquidation CROSS 分支用——
     * 账户级 marginBalance/maintMargin 聚合(marginBalance = paper_balance.free + SUM(unrealizedPnl),
     * maintMargin = SUM(notional × 0.5%))。
     *
     * <p>复用 {@link PositionMapper#findByAccount(long)} + Java filter {@code marginMode==CROSS} +
     * {@code !isFlat()}。PAPER 持仓数小,service 层过滤够用,不加 mapper 查询。
     * paper_trading 由 account 级保证(PAPER account 的 positions 全是 PAPER)。
     *
     * @param accountId 账户 ID
     * @return 该账户所有非 flat 的 CROSS PERP 持仓(跨 symbol);无则空 List
     */
    public List<Position> findCrossPerpByAccount(long accountId) {
        return positionMapper.findByAccount(accountId).stream()
                .filter(p -> p.getMarginMode() == MarginMode.CROSS)
                .filter(p -> !p.isFlat())
                .toList();
    }
}
