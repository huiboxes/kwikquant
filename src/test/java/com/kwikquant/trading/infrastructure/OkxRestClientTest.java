package com.kwikquant.trading.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kwikquant.shared.infra.ExchangeException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link OkxRestClient} 纯函数单测:签名 + 时间戳格式 + JSON data 解析。
 *
 * <p>真实 HTTP 调用(fetchPositions)不可单测(外部 OKX REST),JaCoCo 排除;
 * 本测覆盖可单测的纯函数,sign 用 HMAC-SHA256 RFC 标准测试向量验证实现正确。
 */
class OkxRestClientTest {

    private final OkxRestClient client = new OkxRestClient(null, null, null, new ObjectMapper());

    @Test
    void sign_hmacSha256_rfcVector_matchesKnownValue() {
        // HMAC-SHA256 标准向量:key="key", data="The quick brown fox jumps over the lazy dog"
        // 用 `openssl dgst -hmac key -sha256 -binary | base64` 独立验证(非自指):
        //   97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg=
        assertThat(OkxRestClient.sign("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg=");
    }

    @Test
    void sign_deterministicAndDistinctPerSecret() {
        // 同输入两次签名一致(确定性);不同 secret 结果不同
        String a1 = OkxRestClient.sign("secret-A", "msg");
        String a2 = OkxRestClient.sign("secret-A", "msg");
        String b = OkxRestClient.sign("secret-B", "msg");
        assertThat(a1).isEqualTo(a2);
        assertThat(a1).isNotEqualTo(b);
        // base64 of 32-byte HMAC = 44 chars,无 padding 截断
        assertThat(a1).hasSize(44);
    }

    @Test
    void ts_iso8601UtcMillisWithZSuffix() {
        String ts = OkxRestClient.ts();
        assertThat(ts).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");
    }

    @Test
    void parseDataList_code0WithData_returnsDataList() {
        String body = "{\"code\":\"0\",\"msg\":\"\",\"data\":["
                + "{\"instId\":\"BTC-USDT-SWAP\",\"posSide\":\"long\",\"pos\":\"0.04\"}"
                + "]}";
        var list = client.parseDataList(body);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("instId")).isEqualTo("BTC-USDT-SWAP");
        assertThat(list.get(0).get("posSide")).isEqualTo("long");
    }

    @Test
    void parseDataList_code0EmptyData_returnsEmptyList() {
        // OKX 无持仓返 {"code":"0","data":[]}
        assertThat(client.parseDataList("{\"code\":\"0\",\"data\":[]}")).isEmpty();
    }

    @Test
    void parseDataList_code1_throwsRetryableExchangeException() {
        assertThatThrownBy(() -> client.parseDataList("{\"code\":\"50001\",\"msg\":\"OnMaintenance\"}"))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("50001")
                .hasMessageContaining("OnMaintenance");
    }

    @Test
    void parseDataList_nullOrBlank_returnsEmpty() {
        assertThat(client.parseDataList(null)).isEmpty();
        assertThat(client.parseDataList("")).isEmpty();
        assertThat(client.parseDataList("   ")).isEmpty();
    }

    @Test
    void parseDataList_nonJson_throwsRetryable() {
        assertThatThrownBy(() -> client.parseDataList("not-json-{"))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("非 JSON");
    }

    @Test
    void parseDataList_dataNotList_returnsEmpty() {
        // 防御:code=0 但 data 非数组(不应发生,但安全返空不抛)
        assertThat(client.parseDataList("{\"code\":\"0\",\"data\":\"unexpected\"}"))
                .isEmpty();
    }
}
