package com.kwikquant.shared.infra;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quote 币种配置(独立于 MarketProperties,因 BalanceService 在 account 模块拿不到 market 的 MarketProperties;
 * 参考 {@link ProxyProperties} 同理,放 shared.infra 让 market/account 共用)。
 *
 * <p>{@code allowedCurrencies} 驱动两处:(1) market 的 TradingPairService filter /pairs 只返配置 quote 对;
 * (2) account 的 PaperBalanceAdapter.initBalance/reset 用 {@link #primaryQuoteCurrency()} 作模拟盘主 quote。
 *
 * <p>yaml:
 * <pre>{@code
 * kwikquant:
 *   quote:
 *     allowed-currencies: [USDT]   # 默认 USDT;以后可加 USDC/FDUSD 等
 *     paper-initial-balance: 100000
 * }</pre>
 *
 * <p><b>honest 边界(USDT-only 配置不触发,留账)</b>:多 quote 配置时 PortfolioService.totalUnrealizedPnl
 * 跨币种加法(USDC 持仓 PnL + USDT 持仓 PnL 直接 add)、USDT 脱钩时 USDC 余额折算 USDT 估值失真
 * ——本配置层不解,需 L2 估值口径改造。
 */
@ConfigurationProperties(prefix = "kwikquant.quote")
public record QuoteCurrencyProperties(List<String> allowedCurrencies, BigDecimal paperInitialBalance) {

    private static final List<String> DEFAULT_ALLOWED = List.of("USDT");
    private static final BigDecimal DEFAULT_PAPER_INITIAL = new BigDecimal("100000");

    public QuoteCurrencyProperties {
        if (allowedCurrencies == null || allowedCurrencies.isEmpty()) {
            allowedCurrencies = DEFAULT_ALLOWED;
        }
        if (paperInitialBalance == null) {
            paperInitialBalance = DEFAULT_PAPER_INITIAL;
        }
    }

    /** 模拟盘主 quote 币种(initBalance/reset 用)。配置非空保证 get(0) 安全。 */
    public String primaryQuoteCurrency() {
        return allowedCurrencies().get(0);
    }

    /** get-prefixed 访问器(对齐 Spring/Jackson 习惯 + 测试契约)。 */
    public List<String> getAllowedCurrencies() {
        return allowedCurrencies();
    }

    /** get-prefixed 访问器(对齐 Spring/Jackson 习惯 + 测试契约)。 */
    public BigDecimal getPaperInitialBalance() {
        return paperInitialBalance();
    }
}
