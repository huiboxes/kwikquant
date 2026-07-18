package com.kwikquant.shared.infra;

import java.util.Map;

/**
 * 把 {@link ProxyProperties.ProxyConfig} 应用到 CCXT Exchange 实例。静态工具。
 *
 * <p>两步,不能合一:CCXT Java 的 {@code Binance(config)}/{@code Okx(config)}/{@code Bitget(config)}
 * 在构造时读 config map,构造后改 {@code httpsProxy} 字段不生效(实测现状代码就是构造前 put config)。
 * 故 {@link #applyRest} 构造前塞 config map,{@link #applyWs} 构造后设 {@code wsSocksProxy} 字段。
 *
 * <p>替代 {@code CcxtExchangeRegistry}/{@code BalanceService}/{@code OkxConnectivitySmokeTest}
 * 三处重复的 env 读取 + toHttpProxy/toSocksProxy 派生逻辑。
 */
public final class CcxtProxyApplier {

    private CcxtProxyApplier() {}

    /** 构造前:往 config map 塞 httpsProxy(REST)。{@code direct} 或 null p 跳过;restProxy null 跳过。 */
    public static void applyRest(Map<String, Object> config, ProxyProperties.ProxyConfig p) {
        if (p == null || p.direct()) {
            return;
        }
        if (p.restProxy() != null) {
            config.put("httpsProxy", p.restProxy());
        }
    }

    /** 构造后:设 wsSocksProxy 字段(WS,socks5)。{@code direct} 或 null p 跳过;wsProxy null 跳过。 */
    public static void applyWs(io.github.ccxt.Exchange ex, ProxyProperties.ProxyConfig p) {
        if (p == null || p.direct()) {
            return;
        }
        if (p.wsProxy() != null) {
            ex.wsSocksProxy = p.wsProxy();
        }
    }

    /**
     * smoke test 专用:从单 socks5:// url 派生 rest(http://)+ws(socks5://)。
     * 生产不用(yaml 直接配 rest+ws 两字段)。null/blank → null(直连)。
     */
    public static ProxyProperties.ProxyConfig fromSocksUrl(String socksUrl) {
        if (socksUrl == null || socksUrl.isBlank()) {
            return null;
        }
        String httpUrl = socksUrl.replaceFirst("^socks5h?://", "http://");
        return new ProxyProperties.ProxyConfig(httpUrl, socksUrl, false);
    }
}
