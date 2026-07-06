package com.kwikquant.account.application;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.account.infrastructure.PaperBalanceAdapter;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.OrderSide;
import io.github.ccxt.exchanges.pro.Binance;
import io.github.ccxt.exchanges.pro.Bitget;
import io.github.ccxt.exchanges.pro.Okx;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BalanceService {

    private static final Logger log = LoggerFactory.getLogger(BalanceService.class);

    private final ExchangeAccountService accountService;
    private final KeyManagementService keyManagementService;
    private final PaperBalanceAdapter paperBalanceAdapter;

    public BalanceService(
            ExchangeAccountService accountService,
            KeyManagementService keyManagementService,
            PaperBalanceAdapter paperBalanceAdapter) {
        this.accountService = accountService;
        this.keyManagementService = keyManagementService;
        this.paperBalanceAdapter = paperBalanceAdapter;
    }

    /**
     * 查询账户余额。PAPER 委托 {@link PaperBalanceAdapter#fetch}(读 paper_balances 真实余额);
     * 真实交易所走 CCXT {@code fetchBalance} 实时拉。
     *
     * <p>分发 {@code exchange==PAPER} 是端口选择(同 OrderRouter 分流),非业务分支。
     */
    public BalanceSnapshot fetchBalance(long accountId, long userId) {
        ExchangeAccount account = accountService.getOwned(accountId, userId);

        if (account.getExchange() == Exchange.PAPER) {
            return paperBalanceAdapter.fetch(account);
        }

        io.github.ccxt.Exchange ccxt = createAuthenticatedExchange(account);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) ccxt.fetchBalance();

            return parseBalance(raw);
        } catch (Exception e) {
            log.error("[balance] fetchBalance failed for accountId={}: {}", accountId, e.getMessage());
            throw new ExchangeException("fetchBalance failed: " + e.getMessage(), e, true);
        }
    }

    /**
     * 冻结余额(挂单预占)。仅 PAPER 委托 paperBalanceAdapter;真实交易所余额由交易所维护,
     * 本地不冻结,静默 noop。
     */
    public void freeze(long accountId, Exchange exchange, String currency, BigDecimal amount) {
        if (exchange != Exchange.PAPER) return;
        paperBalanceAdapter.freeze(accountId, currency, amount);
    }

    /** 解冻余额(撤单 / submit 失败补偿)。仅 PAPER;真实交易所 noop。 */
    public void unfreeze(long accountId, Exchange exchange, String currency, BigDecimal amount) {
        if (exchange != Exchange.PAPER) return;
        paperBalanceAdapter.unfreeze(accountId, currency, amount);
    }

    /**
     * 应用成交到余额。仅 PAPER 委托 paperBalanceAdapter;真实交易所成交已由交易所扣余额,本地不记账,noop。
     */
    public void applyFill(
            long accountId,
            Exchange exchange,
            OrderSide side,
            String symbol,
            BigDecimal qty,
            BigDecimal price,
            BigDecimal fee) {
        if (exchange != Exchange.PAPER) return;
        paperBalanceAdapter.applyFill(accountId, side, symbol, qty, price, fee);
    }

    /**
     * 重置模拟盘余额:清空余额行,重插 10 万 USDT。仅 PAPER 账户;真实账户抛
     * {@link IllegalArgumentException}(重置只对 PAPER)。持仓/挂单清理由 trading 侧重置端点负责。
     */
    public void reset(long accountId, Exchange exchange) {
        if (exchange != Exchange.PAPER) {
            throw new IllegalArgumentException("reset only supported for PAPER account, got: " + exchange);
        }
        paperBalanceAdapter.reset(accountId);
    }

    private io.github.ccxt.Exchange createAuthenticatedExchange(ExchangeAccount account) {
        String apiKey = account.getApiKey();
        byte[] secretBytes = keyManagementService.decryptSecret(account);
        String secret = new String(secretBytes, StandardCharsets.UTF_8);
        byte[] passphraseBytes = keyManagementService.decryptPassphrase(account);
        String passphrase = passphraseBytes != null ? new String(passphraseBytes, StandardCharsets.UTF_8) : null;

        String proxyUrl = System.getenv("CCXT_PROXY");

        io.github.ccxt.Exchange ex =
                switch (account.getExchange()) {
                    case BINANCE -> {
                        var e = new Binance(new HashMap<>());
                        e.apiKey = apiKey;
                        e.secret = secret;
                        if (proxyUrl != null && !proxyUrl.isBlank()) e.socksProxy = proxyUrl;
                        yield e;
                    }
                    case OKX -> {
                        var e = new Okx(new HashMap<>());
                        e.apiKey = apiKey;
                        e.secret = secret;
                        if (passphrase != null) e.password = passphrase;
                        if (proxyUrl != null && !proxyUrl.isBlank()) e.socksProxy = proxyUrl;
                        yield e;
                    }
                    case BITGET -> {
                        var e = new Bitget(new HashMap<>());
                        e.apiKey = apiKey;
                        e.secret = secret;
                        if (passphrase != null) e.password = passphrase;
                        if (proxyUrl != null && !proxyUrl.isBlank()) e.socksProxy = proxyUrl;
                        yield e;
                    }
                    // PAPER 在 fetchBalance 已委托 paperBalanceAdapter,不会进此方法
                    default -> throw new ExchangeException("unsupported exchange: " + account.getExchange(), true);
                };

        // B 路线(testnet)预留:如启用 setSandboxMode(true),在此处开启。当前路线 A(自建 paper)不启用。
        return ex;
    }

    @SuppressWarnings("unchecked")
    private BalanceSnapshot parseBalance(Map<String, Object> raw) {
        Map<String, BalanceSnapshot.CurrencyBalance> currencies = new LinkedHashMap<>();

        Map<String, Object> total = (Map<String, Object>) raw.getOrDefault("total", Map.of());
        Map<String, Object> free = (Map<String, Object>) raw.getOrDefault("free", Map.of());
        Map<String, Object> used = (Map<String, Object>) raw.getOrDefault("used", Map.of());

        for (String currency : total.keySet()) {
            BigDecimal totalAmt = toBigDecimal(total.get(currency));
            if (totalAmt == null || totalAmt.signum() == 0) continue;
            BigDecimal freeAmt = toBigDecimal(free.get(currency));
            BigDecimal usedAmt = toBigDecimal(used.get(currency));
            currencies.put(
                    currency,
                    new BalanceSnapshot.CurrencyBalance(
                            freeAmt != null ? freeAmt : BigDecimal.ZERO,
                            usedAmt != null ? usedAmt : BigDecimal.ZERO,
                            totalAmt));
        }
        return new BalanceSnapshot(currencies);
    }

    private static BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
