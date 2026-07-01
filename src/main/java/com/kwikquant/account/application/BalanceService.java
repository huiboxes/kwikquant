package com.kwikquant.account.application;

import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
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

    public BalanceService(ExchangeAccountService accountService, KeyManagementService keyManagementService) {
        this.accountService = accountService;
        this.keyManagementService = keyManagementService;
    }

    public BalanceSnapshot fetchBalance(long accountId, long userId) {
        ExchangeAccount account = accountService.getOwned(accountId, userId);

        if (account.getExchange() == Exchange.PAPER) {
            return BalanceSnapshot.paper();
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
                    default -> throw new ExchangeException("unsupported exchange: " + account.getExchange(), true);
                };

        if (account.isPaperTrading()) {
            ex.setSandboxMode(true);
        }
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
