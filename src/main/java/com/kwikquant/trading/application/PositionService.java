package com.kwikquant.trading.application;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.shared.types.PositionEffect;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.domain.PositionSide;
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
 * <p>合约语义的开仓/加仓/减仓/平仓反手计算规则见 {@link #applyDelta}(SPOT)与 {@link #applyPerpDelta}(PERP)实现。
 * 现货场景 side 始终 long 或 flat。
 *
 * <p>PERP 合约持仓:SPOT 沿用 {@link #applyDelta} 反手分支(逐字保留),
 * PERP 走 {@link #applyPerpDelta} 按 {@link PositionEffect} 四向分桶,无反手。
 */
@Service
public class PositionService {

    private final PositionMapper positionMapper;

    @Autowired
    public PositionService(PositionMapper positionMapper) {
        this.positionMapper = positionMapper;
    }

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
        return applyFill(accountId, symbol, side, qty, price, fee, MarketType.SPOT, null, null, null);
    }

    /**
     * 按 positionSide 找本地 PERP 持仓(档位 B 实盘强平/ADL 同步用)。
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
     * 反向减仓/平仓/平仓反手 = directionalPnl),供 ExecutionService 回填 fills.realized_pnl_delta。
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
                String posSide = positionEffect.toPositionSide();
                Position p =
                        positionMapper.findByAccountSymbolPosition(accountId, symbol, posSide, marginMode, leverage);
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
                BigDecimal realizedPnlDelta = applyDelta(p, side, qty, price, fee);
                int affected = positionMapper.casUpdate(p);
                if (affected == 1) {
                    p.setVersion(p.getVersion() + 1);
                    return realizedPnlDelta;
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

    /**
     * 在已存在持仓上叠加一笔成交(SPOT)。修改 p 的字段,由调用方持久化。反手分支逐字保留。
     *
     * @return 本次 fill 的已实现盈亏增量(开仓/加仓 = ZERO;反向减仓/平仓/平仓反手 = 本次平仓部分的
     *         平仓 PnL,directionalPnl),供 ExecutionService 回填 fills.realized_pnl_delta。
     */
    static BigDecimal applyDelta(Position p, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal fee) {
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
            return BigDecimal.ZERO;
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
     *   <li>fillQty &gt; position.qty → 抛 {@link RejectFillException}(优先抛非 cap)</li>
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
            p.setLiquidationPrice(p.computeLiquidationPrice(Position.DEFAULT_MAINT_MARGIN_RATE));
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
