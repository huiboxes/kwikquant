package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ccxt.exchanges.pro.Okx;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CcxtProxyApplierTest {

    @Test
    void applyRest_whenDirect_skipsConfig() {
        var direct = new ProxyProperties.ProxyConfig(null, null, true);
        var config = new HashMap<String, Object>();
        CcxtProxyApplier.applyRest(config, direct);
        assertThat(config).doesNotContainKey("httpsProxy");
    }

    @Test
    void applyRest_whenNullProxy_skips() {
        var config = new HashMap<String, Object>();
        CcxtProxyApplier.applyRest(config, null);
        assertThat(config).doesNotContainKey("httpsProxy");
    }

    @Test
    void applyRest_whenHasRestProxy_putsHttpsProxy() {
        var p = new ProxyProperties.ProxyConfig("http://127.0.0.1:13659", null, false);
        var config = new HashMap<String, Object>();
        CcxtProxyApplier.applyRest(config, p);
        assertThat(config).containsEntry("httpsProxy", "http://127.0.0.1:13659");
    }

    @Test
    void applyRest_whenRestProxyNull_skips() {
        // ws-only 配置(rest null)→ 不塞 httpsProxy
        var p = new ProxyProperties.ProxyConfig(null, "socks5://x", false);
        var config = new HashMap<String, Object>();
        CcxtProxyApplier.applyRest(config, p);
        assertThat(config).doesNotContainKey("httpsProxy");
    }

    @Test
    void applyWs_whenDirect_skips() {
        var direct = new ProxyProperties.ProxyConfig(null, null, true);
        var ex = new Okx(Map.of());
        CcxtProxyApplier.applyWs(ex, direct);
        assertThat(ex.wsSocksProxy).isNull();
    }

    @Test
    void applyWs_whenNullProxy_skips() {
        var ex = new Okx(Map.of());
        CcxtProxyApplier.applyWs(ex, null);
        assertThat(ex.wsSocksProxy).isNull();
    }

    @Test
    void applyWs_whenHasWsProxy_setsField() {
        var p = new ProxyProperties.ProxyConfig(null, "socks5://127.0.0.1:13659", false);
        var ex = new Okx(Map.of());
        CcxtProxyApplier.applyWs(ex, p);
        assertThat(ex.wsSocksProxy).isEqualTo("socks5://127.0.0.1:13659");
    }

    @Test
    void applyWs_whenWsProxyNull_skips() {
        var p = new ProxyProperties.ProxyConfig("http://x", null, false);
        var ex = new Okx(Map.of());
        CcxtProxyApplier.applyWs(ex, p);
        assertThat(ex.wsSocksProxy).isNull();
    }

    @Test
    void fromSocksUrl_whenSocks5_derivesHttpAndWs() {
        var p = CcxtProxyApplier.fromSocksUrl("socks5://127.0.0.1:13659");
        assertThat(p.restProxy()).isEqualTo("http://127.0.0.1:13659");
        assertThat(p.wsProxy()).isEqualTo("socks5://127.0.0.1:13659");
        assertThat(p.direct()).isFalse();
    }

    @Test
    void fromSocksUrl_whenSocks5h_derivesHttp() {
        var p = CcxtProxyApplier.fromSocksUrl("socks5h://127.0.0.1:13659");
        assertThat(p.restProxy()).isEqualTo("http://127.0.0.1:13659");
        assertThat(p.wsProxy()).isEqualTo("socks5h://127.0.0.1:13659");
    }

    @Test
    void fromSocksUrl_whenNull_returnsNull() {
        assertThat(CcxtProxyApplier.fromSocksUrl(null)).isNull();
    }

    @Test
    void fromSocksUrl_whenBlank_returnsNull() {
        assertThat(CcxtProxyApplier.fromSocksUrl("  ")).isNull();
    }
}
