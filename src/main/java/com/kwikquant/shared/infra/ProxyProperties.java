package com.kwikquant.shared.infra;

import com.kwikquant.shared.types.Exchange;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CCXT 代理配置(独立于 MarketProperties,因 BalanceService 在 account 模块拿不到 market 的 MarketProperties)。
 *
 * <p>全局 {@code defaults} + 按交易所 {@code overrides}。{@link #resolve(Exchange)} 返回最终生效的 ProxyConfig。
 *
 * <p>yaml:
 * <pre>{@code
 * kwikquant:
 *   proxy:
 *     defaults:
 *       rest-proxy: http://127.0.0.1:13659
 *       ws-proxy: socks5://127.0.0.1:13659
 *     overrides:
 *       BINANCE: { direct: true }   # Binance 直连覆盖全局
 * }</pre>
 *
 * <p>{@code defaults} 或 {@code overrides} 在 yaml 缺省时为 null,record 紧凑构造器把 null overrides
 * 归一为空 Map(防 resolve NPE)。{@code defaults}=null 表示无全局代理 → 所有交易所直连(除非 override 显式配)。
 */
@ConfigurationProperties(prefix = "kwikquant.proxy")
public record ProxyProperties(ProxyConfig defaults, Map<Exchange, ProxyConfig> overrides) {

    /**
     * REST(httpsProxy,http://)与 WS(wsSocksProxy,socks5://)独立两字段。
     * {@code direct=true} 强制直连(忽略两 url)。字段 null=该协议不设代理。
     */
    public record ProxyConfig(String restProxy, String wsProxy, boolean direct) {}

    public ProxyProperties {
        if (overrides == null) {
            overrides = Map.of();
        }
    }

    /**
     * resolve 语义(对齐 spec 决策 3 "字段级 override"):
     * <ol>
     *   <li>overrides[ex] 存在且 direct=true → 直连(new ProxyConfig(null,null,true))</li>
     *   <li>overrides[ex] 存在(非 direct) → 字段级 merge 到 defaults:
     *       rest = override.restProxy != null ? override.restProxy : defaults.restProxy
     *       ws   = override.wsProxy   != null ? override.wsProxy   : defaults.wsProxy
     *       (override 未写字段=null → 沿用 defaults 对应字段)</li>
     *   <li>无 override → 用 defaults</li>
     *   <li>defaults == null → 直连</li>
     * </ol>
     */
    public ProxyConfig resolve(Exchange ex) {
        ProxyConfig override = overrides.get(ex);
        if (override != null && override.direct()) {
            return new ProxyConfig(null, null, true);
        }
        if (override != null) {
            String rest = override.restProxy() != null
                    ? override.restProxy()
                    : (defaults != null ? defaults.restProxy() : null);
            String ws =
                    override.wsProxy() != null ? override.wsProxy() : (defaults != null ? defaults.wsProxy() : null);
            return new ProxyConfig(rest, ws, false);
        }
        if (defaults != null) {
            return defaults;
        }
        return new ProxyConfig(null, null, true);
    }
}
