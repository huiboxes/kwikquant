package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.shared.types.Exchange;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyPropertiesTest {

    private static final ProxyProperties.ProxyConfig GLOBAL =
            new ProxyProperties.ProxyConfig("http://g:13659", "socks5://g:13659", false);

    @Test
    void resolve_whenNoOverride_returnsDefaults() {
        var p = new ProxyProperties(GLOBAL, Map.of());
        assertThat(p.resolve(Exchange.OKX)).isEqualTo(GLOBAL);
    }

    @Test
    void resolve_whenOverrideDirectTrue_forcesDirect() {
        var p = new ProxyProperties(
                GLOBAL, Map.of(Exchange.BINANCE, new ProxyProperties.ProxyConfig(null, null, true)));
        var resolved = p.resolve(Exchange.BINANCE);
        assertThat(resolved.direct()).isTrue();
        assertThat(resolved.restProxy()).isNull();
        assertThat(resolved.wsProxy()).isNull();
    }

    @Test
    void resolve_whenOverrideHasRestOnly_mergesWsFromDefaults() {
        // override 只写 rest → ws 沿用 defaults(字段级 merge,null 字段 fallback defaults)
        var p = new ProxyProperties(
                GLOBAL, Map.of(Exchange.BITGET, new ProxyProperties.ProxyConfig("http://b:8080", null, false)));
        var resolved = p.resolve(Exchange.BITGET);
        assertThat(resolved.restProxy()).isEqualTo("http://b:8080");
        assertThat(resolved.wsProxy()).isEqualTo("socks5://g:13659");
        assertThat(resolved.direct()).isFalse();
    }

    @Test
    void resolve_whenOverrideHasWsOnly_mergesRestFromDefaults() {
        var p = new ProxyProperties(
                GLOBAL, Map.of(Exchange.BITGET, new ProxyProperties.ProxyConfig(null, "socks5://b:1080", false)));
        var resolved = p.resolve(Exchange.BITGET);
        assertThat(resolved.restProxy()).isEqualTo("http://g:13659");
        assertThat(resolved.wsProxy()).isEqualTo("socks5://b:1080");
    }

    @Test
    void resolve_whenNoDefaultsNoOverride_isDirect() {
        var p = new ProxyProperties(null, Map.of());
        assertThat(p.resolve(Exchange.OKX).direct()).isTrue();
    }

    @Test
    void resolve_whenOverridesNullInConstructor_normalizedToEmptyMap() {
        // record 紧凑构造器把 null overrides → Map.of(),防 resolve NPE
        var p = new ProxyProperties(GLOBAL, null);
        assertThat(p.resolve(Exchange.OKX)).isEqualTo(GLOBAL);
        assertThat(p.overrides()).isEmpty();
    }

    @Test
    void resolve_whenOverrideNonDirectButBothFieldsNull_fallsBackToDefaultsFields() {
        // override 非 direct 且两字段 null → merge 全用 defaults
        var p = new ProxyProperties(GLOBAL, Map.of(Exchange.OKX, new ProxyProperties.ProxyConfig(null, null, false)));
        assertThat(p.resolve(Exchange.OKX)).isEqualTo(GLOBAL);
    }
}
