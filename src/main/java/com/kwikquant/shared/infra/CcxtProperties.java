package com.kwikquant.shared.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CCXT 实盘配置(独立于 {@link ProxyProperties},因 sandbox 开关不属代理范畴,属交易所环境维度)。
 *
 * <p>{@code sandbox} 开关驱动 OKX demo/生产切换,两处共用同一信号(source of truth):
 * <ol>
 *   <li>{@code CcxtAuthExchangeFactory.createAuthExchange} → {@code setSandboxMode(true)}
 *       (影响 CCXT 内部 endpoint + x-simulated-trading header,4a.3 createOrder/setLeverage 链路);</li>
 *   <li>{@code OkxRestClient} 直调 OKX REST → {@code x-simulated-trading:1} header
 *       (绕 CCXT Java 4.5.67 基类 fetchPositions 返空 bug,4a.4 fetchSnapshot)。</li>
 * </ol>
 *
 * <p><b>信号源取舍(架构师)</b>:全局环境维度(dev=testnet / prod=production)是真实维度,
 * 配置开关符合;per-account testnet 共存(同交易所 demo+prod key)是边缘场景,留账精细化
 * (需 {@code ExchangeAccount} 加 {@code testnet} 字段 + Flyway V33+,架构 followup)。
 * {@code paperTrading} 同有全局+per-account 两层先例,本配置是 per-account 字段的前置 fallback。
 *
 * <p>yaml:
 * <pre>{@code
 * kwikquant:
 *   ccxt:
 *     sandbox: false   # prod 默认 false(生产 key 直连);dev/test override true(OKX demo key)
 * }</pre>
 */
@ConfigurationProperties(prefix = "kwikquant.ccxt")
public record CcxtProperties(boolean sandbox) {

    // record primitive boolean 未配时 default false(prod 直连生产)。dev/test yaml override true。
}
