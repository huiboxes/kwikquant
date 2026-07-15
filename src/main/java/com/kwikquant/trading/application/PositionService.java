package com.kwikquant.trading.application;

import com.kwikquant.shared.types.OrderSide;
import com.kwikquant.trading.domain.InsufficientBalanceException;
import com.kwikquant.trading.domain.Position;
import com.kwikquant.trading.infrastructure.ConcurrencyConflictException;
import com.kwikquant.trading.infrastructure.PositionMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 持仓服务。被 ExecutionService 在 §1.4 事务内调用。<strong>不开启自己的事务。</strong>
 *
 * <p>合约语义的开仓 / 加仓 / 减仓 / 平仓反手计算规则详见 wave4-tech-design §3.9。
 * 现货场景 side 始终 long 或 flat。
 */
@Service
public class PositionService {

    private final PositionMapper positionMapper;

    @Autowired
    public PositionService(PositionMapper positionMapper) {
        this.positionMapper = positionMapper;
    }

    /**
     * 应用一笔成交到持仓。CAS 冲突重试 {@value TradingConstants#MAX_CAS_RETRIES} 次，
     * 超限抛 {@link ConcurrencyConflictException}（→ 上游事务回滚）。
     */
    public void applyFill(
            long accountId, String symbol, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal fee) {
        for (int attempt = 0; attempt < TradingConstants.MAX_CAS_RETRIES; attempt++) {
            Position p = positionMapper.findByAccountAndSymbol(accountId, symbol);
            if (p == null) {
                p = newPosition(accountId, symbol, side, qty, price, fee);
                try {
                    positionMapper.insert(p);
                    return;
                } catch (org.springframework.dao.DuplicateKeyException ex) {
                    // 并发首次 insert 撞键 → 重试取已有
                    continue;
                }
            }
            applyDelta(p, side, qty, price, fee);
            int affected = positionMapper.casUpdate(p);
            if (affected == 1) {
                p.setVersion(p.getVersion() + 1);
                return;
            }
            // CAS 冲突，重试
        }
        throw new ConcurrencyConflictException("Position CAS failed after " + TradingConstants.MAX_CAS_RETRIES
                + " retries: account=" + accountId + " symbol=" + symbol);
    }

    private Position newPosition(
            long accountId, String symbol, OrderSide side, BigDecimal qty, BigDecimal price, BigDecimal fee) {
        Position p = Position.flat(accountId, symbol);
        p.setSide(side == OrderSide.BUY ? Position.SIDE_LONG : Position.SIDE_SHORT);
        p.setQty(qty);
        p.setAvgEntryPrice(price);
        p.setRealizedPnl(fee.negate());
        return p;
    }

    /** 在已存在持仓上叠加一笔成交。修改 p 的字段，由调用方持久化。 */
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

    /** 仅做粗略保证金校验（现货场景）。Wave 5 RiskGate 完整覆盖。 */
    @SuppressWarnings("unused")
    void requireBalance(BigDecimal required, BigDecimal available) {
        if (available == null || available.compareTo(required) < 0) {
            throw new InsufficientBalanceException(
                    "insufficient balance: required=" + required + " available=" + available);
        }
    }

    public java.util.List<Position> findByAccount(long accountId) {
        return positionMapper.findByAccount(accountId);
    }

    public Position findByAccountAndSymbol(long accountId, String symbol) {
        return positionMapper.findByAccountAndSymbol(accountId, symbol);
    }

    public Position findById(long id) {
        return positionMapper.findById(id);
    }
}
