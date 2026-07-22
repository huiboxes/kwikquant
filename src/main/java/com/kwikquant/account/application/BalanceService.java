package com.kwikquant.account.application;

import static com.kwikquant.shared.types.NumberUtils.asBd;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.infra.QuoteCurrencyProperties;
import com.kwikquant.shared.types.MarketType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BalanceService {

    private static final Logger log = LoggerFactory.getLogger(BalanceService.class);

    private final ExchangeAccountService accountService;
    private final PaperBalanceAdapter paperBalanceAdapter;
    private final QuoteCurrencyProperties quoteCurrencyProperties;
    private final CcxtAuthExchangeFactory ccxtAuthExchangeFactory;

    public BalanceService(
            ExchangeAccountService accountService,
            PaperBalanceAdapter paperBalanceAdapter,
            QuoteCurrencyProperties quoteCurrencyProperties,
            CcxtAuthExchangeFactory ccxtAuthExchangeFactory) {
        this.accountService = accountService;
        this.paperBalanceAdapter = paperBalanceAdapter;
        this.quoteCurrencyProperties = quoteCurrencyProperties;
        this.ccxtAuthExchangeFactory = ccxtAuthExchangeFactory;
    }

    /**
     * 查询账户余额。模拟盘委托 {@link PaperBalanceAdapter#fetch}(读 paper_balances 真实余额);
     * 真实交易所走 CCXT {@code fetchBalance} 实时拉。
     *
     * <p>分发依据是 {@code isPaperTrading()}（唯一的模式判定字段），不是 {@code exchange}——
     * {@code exchange} 只表示撮合/定价参考哪个真实交易所，模拟盘和实盘的 exchange 都可能是同一个值。
     */
    public BalanceSnapshot fetchBalance(long accountId, long userId) {
        ExchangeAccount account = accountService.getOwned(accountId, userId);

        if (account.isPaperTrading()) {
            return paperBalanceAdapter.fetch(account);
        }

        io.github.ccxt.Exchange ccxt = ccxtAuthExchangeFactory.createAuthExchange(account, MarketType.SPOT);
        try {
            // 4.5.67:基类 fetchBalance 可能返 CompletableFuture(Async)或强类型 Balances;兼容两种
            Object r = ccxt.fetchBalance();
            if (r instanceof java.util.concurrent.CompletableFuture<?> cf) {
                r = cf.join();
            }
            Map<String, Object> raw;
            if (r instanceof io.github.ccxt.types.Balances b) {
                raw = new java.util.LinkedHashMap<>();
                raw.put("total", b.total);
                raw.put("free", b.free);
                raw.put("used", b.used);
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) r;
                raw = m;
            }
            return parseBalance(raw);
        } catch (Exception e) {
            log.error("[balance] fetchBalance failed for accountId={}: {}", accountId, e.getMessage());
            throw new ExchangeException("fetchBalance failed: " + e.getMessage(), e, true);
        }
    }

    /**
     * 冻结余额(挂单预占)。仅模拟盘委托 paperBalanceAdapter;真实交易所余额由交易所维护,
     * 本地不冻结,静默 noop。
     */
    public void freeze(long accountId, boolean paperTrading, String currency, BigDecimal amount) {
        if (!paperTrading) return;
        paperBalanceAdapter.freeze(accountId, currency, amount);
    }

    /** 解冻余额(撤单 / submit 失败补偿)。仅模拟盘;真实交易所 noop。 */
    public void unfreeze(long accountId, boolean paperTrading, String currency, BigDecimal amount) {
        if (!paperTrading) return;
        paperBalanceAdapter.unfreeze(accountId, currency, amount);
    }

    /**
     * 应用成交到余额。仅模拟盘委托 paperBalanceAdapter;真实交易所成交已由交易所扣余额,本地不记账,noop。
     *
     * <p>中转层从 {@link FillCommand} 取 {@code marketType}/{@code positionEffect} 透传给
     * {@link PaperBalanceAdapter#applyFill},ExecutionService 无感(SPOT 场景 FillCommand 两字段为 null,
     * PaperBalanceAdapter 按 null 走原 SPOT BUY/SELL 分支)。
     */
    public void applyFill(FillCommand cmd) {
        if (!cmd.paperTrading()) return;
        paperBalanceAdapter.applyFill(
                cmd.accountId(),
                cmd.side(),
                cmd.symbol(),
                cmd.qty(),
                cmd.price(),
                cmd.fee(),
                cmd.frozenQuoteAmount(),
                cmd.marketType(),
                cmd.positionEffect());
    }

    /**
     * PERP CLOSE_* 平仓 PnL 结算(§3.4)。仅模拟盘委托 paperBalanceAdapter;真实交易所成交已由交易所
     * 扣余额,本地不记账,noop。由 ExecutionService/PositionService 在 applyPerpDelta 算出
     * realizedPnlDelta 后调(同事务 REQUIRED)。
     */
    public void applyPnlSettlement(long accountId, boolean paperTrading, String currency, BigDecimal pnlDelta) {
        if (!paperTrading) return;
        paperBalanceAdapter.applyPnlSettlement(accountId, currency, pnlDelta);
    }

    /**
     * 强平扣减(§11 B3-s)。仅模拟盘委托 paperBalanceAdapter(含负余额保护 clamp);真实交易所强平
     * 由交易所侧扣减,本地 noop。由 ExecutionService.processLiquidation 调(同事务 REQUIRED)。
     */
    public void applyLiquidationDelta(
            long accountId, boolean paperTrading, String currency, BigDecimal dFree, BigDecimal dTotal) {
        if (!paperTrading) return;
        paperBalanceAdapter.applyLiquidationDelta(accountId, currency, dFree, dTotal);
    }

    /**
     * 重置模拟盘余额:清空余额行,重插主 quote 初始额(默认 10 万 USDT,可配)。仅模拟盘账户;真实账户抛
     * {@link IllegalArgumentException}(重置只对模拟盘)。持仓/挂单清理由 trading 侧重置端点负责。
     */
    public void reset(long accountId, boolean paperTrading) {
        if (!paperTrading) {
            throw new IllegalArgumentException("reset only supported for paper accounts");
        }
        paperBalanceAdapter.reset(accountId, quoteCurrencyProperties.primaryQuoteCurrency());
    }

    @SuppressWarnings("unchecked")
    private BalanceSnapshot parseBalance(Map<String, Object> raw) {
        Map<String, BalanceSnapshot.CurrencyBalance> currencies = new LinkedHashMap<>();

        Map<String, Object> total = (Map<String, Object>) raw.getOrDefault("total", Map.of());
        Map<String, Object> free = (Map<String, Object>) raw.getOrDefault("free", Map.of());
        Map<String, Object> used = (Map<String, Object>) raw.getOrDefault("used", Map.of());

        for (String currency : total.keySet()) {
            BigDecimal totalAmt = asBd(total.get(currency));
            if (totalAmt == null || totalAmt.signum() == 0) continue;
            BigDecimal freeAmt = asBd(free.get(currency));
            BigDecimal usedAmt = asBd(used.get(currency));
            currencies.put(
                    currency,
                    new BalanceSnapshot.CurrencyBalance(
                            freeAmt != null ? freeAmt : BigDecimal.ZERO,
                            usedAmt != null ? usedAmt : BigDecimal.ZERO,
                            totalAmt));
        }
        return new BalanceSnapshot(currencies);
    }
}
