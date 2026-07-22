package com.kwikquant.trading.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwikquant.account.application.KeyManagementService;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.CcxtProperties;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.infra.ProxyProperties;
import com.kwikquant.shared.types.Exchange;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OKX REST 直调客户端(4a.4)。绕 CCXT Java 4.5.67 基类 {@code fetchPositions()} 对 OKX 返空 bug
 * (spike OkxAccountModeSpike 验证),用 Java 21 {@link HttpClient} + HMAC-SHA256 手动签名调
 * {@code /api/v5/account/positions} 无 instId 拉账户所有非零持仓,raw list 经
 * {@link OkxOrderTranslator#parsePositionsRest} 解析为 {@link CcxtOrderAdapter.PositionSnapshot}。
 *
 * <p><b>架构师取舍 — 为何不复用 CCXT / 不引库</b>:CCXT 的 {@code createOrder/cancelOrder/
 * setLeverage/setMarginMode} 在 4a.3 全复用了其签名能力(exchange 实例自带 proxy/sandbox/JSON/类型化),
 * <em>唯独</em> fetchPositions 无可用强类型方法——{@code fetchPositionsWs()} NotSupported、基类
 * {@code fetchPositions()} 返空(bug)。故本类手写签名直调 REST,这是 CCXT 干不了的事、被迫且正确,
 * 不是"为不引库牺牲可维护性"。HttpClient 是 JDK 21 自带、Jackson 是 Spring Boot 自带 Bean,均无新依赖。
 *
 * <p><b>职责分离</b>:签名串构造 {@link #sign} + 时间戳 {@link #ts} + JSON data 解析
 * {@link #parseDataList} 为纯函数(便于单测,对照 spike 验证值);HttpClient 副作用部分 JaCoCo 排除
 * (外部 HTTP 不可单测,对齐 {@link DefaultCcxtOrderAdapter})。解密复用 {@link KeyManagementService}
 * (与 {@code CcxtAuthExchangeFactory} 同源不重复),proxy 复用 {@link ProxyProperties},
 * sandbox 复用 {@link CcxtProperties}(与 {@code CcxtAuthExchangeFactory} 同一信号 source of truth)。
 *
 * <p>仅 OKX 实装(Binance/Bitget PERP 留账 §10 B7 单向持仓模式冲突)。fetchOpenOrders 留账(4b)。
 */
@Component
public class OkxRestClient {

    private static final Logger log = LoggerFactory.getLogger(OkxRestClient.class);

    private static final String OKX_REST_BASE = "https://www.okx.com";
    private static final String POSITIONS_PATH = "/api/v5/account/positions";

    /** OKX 时间戳格式:ISO-8601 UTC 毫秒,带 'Z' 后缀。spike 验证可用。 */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final KeyManagementService keyManagementService;
    private final ProxyProperties proxyProperties;
    private final CcxtProperties ccxtProperties;
    private final ObjectMapper objectMapper;

    public OkxRestClient(
            KeyManagementService keyManagementService,
            ProxyProperties proxyProperties,
            CcxtProperties ccxtProperties,
            ObjectMapper objectMapper) {
        this.keyManagementService = keyManagementService;
        this.proxyProperties = proxyProperties;
        this.ccxtProperties = ccxtProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 拉账户所有非零 PERP 持仓(OKX REST 无 instId 返所有非零持仓,spike 验证)。
     *
     * @return raw OKX data list(instId/posSide/pos/avgPx/lever/mgnMode/liqPx/markPx/mmr/upl 等),
     *         供 {@link OkxOrderTranslator#parsePositionsRest} 解析;账户无持仓返空 list。
     * @throws ExchangeException 非 OKX 账户 / HTTP 失败 / OKX code!=0(retryable=true 便于上层重试)
     */
    public List<Map<String, Object>> fetchPositions(ExchangeAccount account) {
        if (account.getExchange() != Exchange.OKX) {
            throw new ExchangeException(
                    "OkxRestClient 仅支持 OKX," + account.getExchange() + " 留账 §10 B7", /*retryable=*/ false);
        }
        String apiKey = account.getApiKey();
        byte[] secretBytes = keyManagementService.decryptSecret(account);
        String secret = new String(secretBytes, StandardCharsets.UTF_8);
        Arrays.fill(secretBytes, (byte) 0);
        byte[] passBytes = keyManagementService.decryptPassphrase(account);
        String passphrase = passBytes != null ? new String(passBytes, StandardCharsets.UTF_8) : null;
        if (passBytes != null) Arrays.fill(passBytes, (byte) 0);

        String ts = ts();
        String sign = sign(secret, ts + "GET" + POSITIONS_PATH);
        HttpRequest req = HttpRequest.newBuilder(URI.create(OKX_REST_BASE + POSITIONS_PATH))
                .header("OK-ACCESS-KEY", apiKey)
                .header("OK-ACCESS-SIGN", sign)
                .header("OK-ACCESS-TIMESTAMP", ts)
                .header("OK-ACCESS-PASSPHRASE", passphrase == null ? "" : passphrase)
                .header("Content-Type", "application/json")
                .header("x-simulated-trading", ccxtProperties.sandbox() ? "1" : "0")
                .GET()
                .build();

        HttpClient.Builder hb = HttpClient.newBuilder();
        InetSocketAddress proxyAddr = resolveProxy();
        if (proxyAddr != null) {
            hb.proxy(ProxySelector.of(proxyAddr));
        }
        HttpResponse<String> resp;
        try {
            resp = hb.build().send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ExchangeException("OKX REST positions 请求失败: " + e.getMessage(), e, /*retryable=*/ true);
        }
        if (resp.statusCode() != 200) {
            throw new ExchangeException(
                    "OKX REST positions HTTP " + resp.statusCode() + ": " + resp.body(), /*retryable=*/ true);
        }
        log.info(
                "[okx-rest] fetchPositions ok: accountId={} sandbox={} bodyLen={}",
                account.getId(),
                ccxtProperties.sandbox(),
                resp.body() == null ? 0 : resp.body().length());
        return parseDataList(resp.body());
    }

    /**
     * 解析 OKX 响应 {@code {code:"0",data:[...]}} → data list。纯函数便于单测。
     *
     * <p>{@code code!=0} 抛 retryable(交易所业务错,如限频/签名错可重试);{@code data} 非 list 返空。
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parseDataList(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return List.of();
        }
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(jsonBody, Map.class);
        } catch (Exception e) {
            throw new ExchangeException("OKX REST 响应非 JSON: " + e.getMessage(), e, /*retryable=*/ true);
        }
        String code = root.get("code") == null ? null : root.get("code").toString();
        if (!"0".equals(code)) {
            String msg = root.get("msg") == null ? "" : root.get("msg").toString();
            throw new ExchangeException("OKX REST code=" + code + " msg=" + msg, /*retryable=*/ true);
        }
        Object data = root.get("data");
        if (data instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /** OKX 签名 = base64(HMAC-SHA256(secret, ts+method+path+body))。GET body 空。纯函数便于单测。 */
    static String sign(String secret, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ExchangeException("OKX HMAC 签名失败: " + e.getMessage(), e, /*retryable=*/ false);
        }
    }

    /** OKX 时间戳(ISO-8601 UTC 毫秒,带 'Z')。纯函数便于单测格式。 */
    static String ts() {
        return TS_FMT.format(Instant.now());
    }

    /** 从 {@link ProxyProperties} 解析 OKX REST 代理地址。direct/null/无 restProxy → null(直连)。 */
    private InetSocketAddress resolveProxy() {
        ProxyProperties.ProxyConfig pc = proxyProperties.resolve(Exchange.OKX);
        if (pc == null || pc.direct() || pc.restProxy() == null) {
            return null;
        }
        try {
            URI u = URI.create(pc.restProxy());
            String host = u.getHost();
            if (host == null) {
                return null;
            }
            int port = u.getPort() != -1 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            return new InetSocketAddress(host, port);
        } catch (Exception e) {
            log.warn("[okx-rest] proxy 解析失败,直连: {} err={}", pc.restProxy(), e.getMessage());
            return null;
        }
    }
}
