package com.kwikquant.account.infrastructure;

import com.kwikquant.account.application.BalancePort;
import com.kwikquant.account.application.BalanceSnapshot;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.domain.InsufficientBalanceException;
import com.kwikquant.account.domain.PaperBalance;
import com.kwikquant.shared.infra.ResourceStateConflictException;
import com.kwikquant.shared.types.OrderSide;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 模拟盘余额适配器:读写 paper_balances 表(PaperExchange 内部持久化状态)。
 *
 * <p>职责:fetch(查余额) + initBalance(创建账户初始化 10 万 USDT) + freeze/unfreeze(挂单冻结/撤单解冻)
 * + applyFill(成交扣减/入账) + reset(重置回初始值)。
 *
 * <p>CAS 乐观锁 + 重试 3 次,模式同 {@code PositionService.applyFill}。所有写操作不带自己的事务
 * (无 @Transactional),加入调用方事务(ExecutionService.processExecutionReport REQUIRED 事务),
 * 保证余额扣减 + 持仓更新 + 订单状态推进 + Fill insert 原子。
 *
 * <p>成交扣减语义(fee 从 quote 扣,MatchingKernel.inferFeeCurrency 统一返回 quote):
 * <ul>
 *   <li>BUY: freeze 时冻结 quote=price*qty; 成交时 quote 解冻 price*qty + 扣 fee, base 入账 qty</li>
 *   <li>SELL: freeze 时冻结 base=qty; 成交时 base 解冻 qty, quote 入账 price*qty-fee</li>
 * </ul>
 */
@Component
public class PaperBalanceAdapter implements BalancePort {

    private static final int MAX_CAS_RETRIES = 3;
    private static final BigDecimal PAPER_INITIAL_USDT = new BigDecimal("100000");

    private final PaperBalanceMapper mapper;

    public PaperBalanceAdapter(PaperBalanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public BalanceSnapshot fetch(ExchangeAccount account) {
        Map<String, BalanceSnapshot.CurrencyBalance> currencies = new LinkedHashMap<>();
        for (PaperBalance b : mapper.findByAccount(account.getId())) {
            currencies.put(
                    b.getCurrency(), new BalanceSnapshot.CurrencyBalance(b.getFree(), b.getUsed(), b.getTotal()));
        }
        return new BalanceSnapshot(currencies);
    }

    /** 创建模拟盘账户时初始化 10 万 USDT。幂等(重复调用静默,reset 重插场景)。 */
    public void initBalance(long accountId) {
        PaperBalance b = new PaperBalance();
        b.setAccountId(accountId);
        b.setCurrency("USDT");
        b.setFree(PAPER_INITIAL_USDT);
        b.setUsed(BigDecimal.ZERO);
        b.setTotal(PAPER_INITIAL_USDT);
        b.setVersion(0);
        try {
            mapper.insert(b);
        } catch (DuplicateKeyException ex) {
            // 账户已有 USDT 行(reset 后重插或重复调用),幂等忽略
        }
    }

    /**
     * 冻结余额(挂单预占)。free -= amount, used += amount, total 不变。
     * 余额不足抛 {@link InsufficientBalanceException}(TradingService.submit catch 转 REJECTED)。
     */
    public void freeze(long accountId, String currency, BigDecimal amount) {
        if (amount.signum() <= 0) return;
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            PaperBalance b = mapper.findByAccountAndCurrency(accountId, currency);
            if (b == null) {
                throw new InsufficientBalanceException(
                        "insufficient balance: currency=" + currency + " free=0 required=" + amount);
            }
            if (b.getFree().compareTo(amount) < 0) {
                throw new InsufficientBalanceException(
                        "insufficient balance: currency=" + currency + " free=" + b.getFree() + " required=" + amount);
            }
            b.setFree(b.getFree().subtract(amount));
            b.setUsed(b.getUsed().add(amount));
            int affected = mapper.casUpdate(b);
            if (affected == 1) {
                b.setVersion(b.getVersion() + 1);
                return;
            }
        }
        throw new ResourceStateConflictException(
                "paper_balance freeze CAS failed: account=" + accountId + " currency=" + currency);
    }

    /** 解冻余额(撤单/submit 失败补偿)。used -= amount, free += amount, total 不变。幂等(无行静默)。 */
    public void unfreeze(long accountId, String currency, BigDecimal amount) {
        if (amount.signum() <= 0) return;
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            PaperBalance b = mapper.findByAccountAndCurrency(accountId, currency);
            if (b == null) return; // 无行可解冻,幂等
            BigDecimal newUsed = b.getUsed().subtract(amount).max(BigDecimal.ZERO);
            BigDecimal newFree = b.getFree().add(amount);
            b.setUsed(newUsed);
            b.setFree(newFree);
            int affected = mapper.casUpdate(b);
            if (affected == 1) {
                b.setVersion(b.getVersion() + 1);
                return;
            }
        }
        throw new ResourceStateConflictException(
                "paper_balance unfreeze CAS failed: account=" + accountId + " currency=" + currency);
    }

    /**
     * 应用成交到余额(ExecutionService.processExecutionReport 调,同事务)。
     * 不校验余额(撮合已发生,必须记账);fee 从 quote 扣(MatchingKernel.inferFeeCurrency 统一 quote)。
     *
     * <p>{@code frozenQuoteAmount}（仅 BUY 单有意义）：挂单时真实冻结的 quote 金额（{@code
     * Order.frozenQuoteAmount}）。不能直接用 {@code price*qty}（本次成交价）当"解冻量"——MARKET 单
     * 冻结价（下单时的 ticker 估价）跟成交价（撮合时的 ask/bid）系统性不同，两者一样只是巧合（LIMIT
     * 单碰巧相等）。传 null 时退化为旧逻辑（用 price*qty 顶替，仅用于兼容没有该字段的历史订单）。
     */
    public void applyFill(
            long accountId,
            OrderSide side,
            String symbol,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal fee,
            BigDecimal frozenQuoteAmount) {
        String[] parts = symbol.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid symbol (expect BASE/QUOTE): " + symbol);
        }
        String base = parts[0];
        String quote = parts[1];
        BigDecimal safeFee = fee == null ? BigDecimal.ZERO : fee;
        if (side == OrderSide.BUY) {
            BigDecimal actualCost = price.multiply(qty);
            // 解冻量用挂单时真实冻结的量，不是本次成交价算出来的量——两者对 MARKET 单会系统性不同
            // （冻结价是下单时的 ticker 估价，成交价是撮合时的 ask/bid），用成交价当解冻量会让
            // used 残留漂移（长期运行累积成明显负数）。差价走 free 调整，total 始终按实际成本算，
            // 不受冻结价估得准不准影响。
            BigDecimal releaseFromUsed = frozenQuoteAmount != null ? frozenQuoteAmount : actualCost;
            BigDecimal freeAdjustment = releaseFromUsed.subtract(actualCost).subtract(safeFee);
            // quote: free += (releaseFromUsed - actualCost - fee), used -= releaseFromUsed, total -= actualCost+fee
            applyDelta(
                    accountId,
                    quote,
                    freeAdjustment,
                    releaseFromUsed.negate(),
                    actualCost.add(safeFee).negate());
            // base: free += qty, total += qty
            applyDelta(accountId, base, qty, BigDecimal.ZERO, qty);
        } else {
            // base: used -= qty(解冻), total -= qty, free 不变
            applyDelta(accountId, base, BigDecimal.ZERO, qty.negate(), qty.negate());
            // quote: free += price*qty-fee, total += price*qty-fee
            BigDecimal quoteGain = price.multiply(qty).subtract(safeFee);
            applyDelta(accountId, quote, quoteGain, BigDecimal.ZERO, quoteGain);
        }
    }

    /**
     * 对某 currency 行叠加 delta(free/used/total)。行不存在则 insert(0 起步)。
     * CAS 重试 3 次,超限抛 {@link ResourceStateConflictException}(→ 上游事务回滚)。
     */
    private void applyDelta(long accountId, String currency, BigDecimal dFree, BigDecimal dUsed, BigDecimal dTotal) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            PaperBalance b = mapper.findByAccountAndCurrency(accountId, currency);
            if (b == null) {
                PaperBalance fresh = new PaperBalance();
                fresh.setAccountId(accountId);
                fresh.setCurrency(currency);
                fresh.setFree(dFree);
                fresh.setUsed(dUsed);
                fresh.setTotal(dTotal);
                fresh.setVersion(0);
                try {
                    mapper.insert(fresh);
                    return;
                } catch (DuplicateKeyException ex) {
                    continue; // 并发首次 insert 撞唯一键,重试取已有行
                }
            }
            b.setFree(b.getFree().add(dFree));
            b.setUsed(b.getUsed().add(dUsed));
            b.setTotal(b.getTotal().add(dTotal));
            int affected = mapper.casUpdate(b);
            if (affected == 1) {
                b.setVersion(b.getVersion() + 1);
                return;
            }
        }
        throw new ResourceStateConflictException("paper_balance CAS failed after " + MAX_CAS_RETRIES
                + " retries: account=" + accountId + " currency=" + currency);
    }

    /** 重置:清空所有余额行,重插 10 万 USDT。 */
    public void reset(long accountId) {
        mapper.deleteByAccount(accountId);
        initBalance(accountId);
    }
}
