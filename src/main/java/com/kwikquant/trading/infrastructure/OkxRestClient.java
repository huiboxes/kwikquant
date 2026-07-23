package com.kwikquant.trading.infrastructure;

import com.kwikquant.account.application.KeyManagementService;
import com.kwikquant.account.domain.ExchangeAccount;
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
import tools.jackson.databind.ObjectMapper;

/**
 * OKX REST 直调客户端(4a.4 + 4b)。绕 CCXT Java 4.5.67 基类 {@code fetchPositions()} 对 OKX 返空 bug
 * (spike OkxAccountModeSpike 验证) + CCXT Java 私有 WS watch* 全 NotSupported(4b OkxWatchMyTradesSpike
 * 验证),用 Java 21 {@link HttpClient} + HMAC-SHA256 手动签名调 OKX REST:
 * <ul>
 *   <li>{@code /api/v5/account/positions} 无 instId 拉账户所有非零持仓(4a.4 fetchSnapshot);</li>
 *   <li>{@code /api/v5/fills} 拉最近 100 条成交(4b 路线 B 轮询替代 WS push,供 subscribeFills)。</li>
 * </ul>
 *
 * <p><b>架构师取舍 — 为何不复用 CCXT / 不引库</b>:CCXT 的 {@code createOrder/cancelOrder/setLeverage/
 * setMarginMode} 在 4a.3 全复用了其签名能力,<em>唯独</em> fetchPositions 返空 bug + 私有 WS watch*
 * 全 NotSupported。故本类手写签名直调 REST,这是 CCXT 干不了的事、被迫且正确,不是"为不引库牺牲可维护性"。
 * HttpClient 是 JDK 21 自带、Jackson 是 Spring Boot 自带 Bean,均无新依赖。
 *
 * <p><b>职责分离</b>:签名串构造 {@link #sign} + 时间戳 {@link #ts} + JSON data 解析
 * {@link #parseDataList} 为纯函数(便于单测,对照 spike/openssl 验证值);HttpClient 副作用部分 JaCoCo 排除
 * (外部 HTTP 不可单测,对齐 {@link DefaultCcxtOrderAdapter})。解密复用 {@link KeyManagementService}
 * (与 {@code CcxtAuthExchangeFactory} 同源不重复),proxy 复用 {@link ProxyProperties},
 * sandbox 复用 account.testnet(与 CcxtAuthExchangeFactory 同一 source of truth)。
 * fetchPositions/fetchFills 共用 {@link #fetchGet} 签名+HttpClient+解析。
 *
 * <p>仅 OKX 实装(Binance/Bitget PERP 留账 §10 B7 单向持仓模式冲突)。
 */
@Component
public class OkxRestClient {

    private static final Logger log = LoggerFactory.getLogger(OkxRestClient.class);

    private static final String OKX_REST_BASE = "https://www.okx.com";
    private static final String POSITIONS_PATH = "/api/v5/account/positions";
    private static final String FILLS_PATH = "/api/v5/trade/fills";
    private static final String OPEN_ORDERS_PATH = "/api/v5/trade/orders-pending";
    private static final String SET_POSITION_MODE_PATH = "/api/v5/account/set-position-mode";

    /** OKX 时间戳格式:ISO-8601 UTC 毫秒,带 'Z' 后缀。spike 验证可用。 */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final KeyManagementService keyManagementService;
    private final ProxyProperties proxyProperties;
    private final ObjectMapper objectMapper;
    /** 复用 HttpClient 实例:连接池/SSL session/TCP keepalive 生效,避免每请求 new 一个导致 SelectorManager 线程堆积。proxy 在构造时确定(只依赖 Exchange.OKX,与 account 无关)。 */
    private final HttpClient httpClient;

    public OkxRestClient(
            KeyManagementService keyManagementService, ProxyProperties proxyProperties, ObjectMapper objectMapper) {
        this.keyManagementService = keyManagementService;
        this.proxyProperties = proxyProperties;
        this.objectMapper = objectMapper;
        this.httpClient = buildHttpClient();
    }

    /** 构造时一次性创建 HttpClient,proxy 从 {@link ProxyProperties} 解析(只依赖 Exchange.OKX)。直连/null/解析失败 → 无 proxy。 */
    private HttpClient buildHttpClient() {
        HttpClient.Builder hb = HttpClient.newBuilder();
        InetSocketAddress proxyAddr = resolveProxy();
        if (proxyAddr != null) {
            hb.proxy(ProxySelector.of(proxyAddr));
        }
        return hb.build();
    }

    /**
     * 拉账户所有非零 PERP 持仓(OKX REST 无 instId 返所有非零持仓,spike 验证)。
     *
     * @return raw OKX data list(instId/posSide/pos/avgPx/lever/mgnMode/liqPx/markPx/mmr/upl 等),
     *         供 {@link OkxOrderTranslator#parsePositionsRest} 解析;账户无持仓返空 list。
     */
    public List<Map<String, Object>> fetchPositions(ExchangeAccount account) {
        return fetchGet(account, POSITIONS_PATH);
    }

    /**
     * 拉账户最近成交(4b 路线 B:轮询 REST 替代 WS push)。OKX {@code /api/v5/fills} 返最近 100 条成交
     * (按 ts desc 最新在前,7 天内),供 {@link DefaultCcxtOrderAdapter#subscribeFills} 周期轮询
     * 对比 last seen fill id,新成交推 {@link CcxtOrderAdapter.FillEvent}。
     *
     * @return raw OKX data list(ordId/tradeId/instId/side/qty/px/fee/feeCcy/ts/execType 等)
     */
    public List<Map<String, Object>> fetchFills(ExchangeAccount account) {
        return fetchGet(account, FILLS_PATH);
    }

    /**
     * 拉账户当前挂单(4b 对账:fetchSnapshot openOrders)。OKX /api/v5/trade/orders-pending 返所有未成交/部分成交挂单,
     * 供 DefaultCcxtOrderAdapter.fetchSnapshot startup 对账(发现本地无记录的挂单)+ parseOpenOrdersRest 解析。
     */
    public List<Map<String, Object>> fetchOpenOrders(ExchangeAccount account) {
        return fetchGet(account, OPEN_ORDERS_PATH);
    }

    /**
     * OKX REST GET 通用方法。委托 {@link #fetch}(GET body 空)。
     */
    private List<Map<String, Object>> fetchGet(ExchangeAccount account, String path) {
        return fetch(account, "GET", path, "");
    }

    /** OKX REST POST 通用方法(签名串含 body)。set-position-mode 用。 */
    private List<Map<String, Object>> fetchPost(ExchangeAccount account, String path, String body) {
        return fetch(account, "POST", path, body);
    }

    /**
     * OKX REST 通用方法(签名 + HttpClient + proxy + sandbox + JSON 解析)。
     *
     * <p>fetchPositions / fetchFills(GET body 空)/ setPositionMode(POST body) 共用——签名串
     * {@code ts+method+path+body}(GET body 空),header 一致(OK-ACCESS-KEY/SIGN/TIMESTAMP/PASSPHRASE
     * + x-simulated-trading + Content-Type),proxy + sandbox 复用 ProxyProperties(account.testnet 决定 sandbox)。
     * {@code code!=0} 抛 retryable。
     */
    private List<Map<String, Object>> fetch(ExchangeAccount account, String method, String path, String body) {
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
        String sign = sign(secret, ts + method + path + body);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(OKX_REST_BASE + path))
                .header("OK-ACCESS-KEY", apiKey)
                .header("OK-ACCESS-SIGN", sign)
                .header("OK-ACCESS-TIMESTAMP", ts)
                .header("OK-ACCESS-PASSPHRASE", passphrase == null ? "" : passphrase)
                .header("Content-Type", "application/json")
                .header("x-simulated-trading", account.isTestnet() ? "1" : "0");
        if ("GET".equals(method)) {
            reqBuilder.GET();
        } else {
            reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        HttpRequest req = reqBuilder.build();

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ExchangeException("OKX REST " + path + " 请求失败: " + e.getMessage(), e, /*retryable=*/ true);
        }
        if (resp.statusCode() != 200) {
            throw new ExchangeException(
                    "OKX REST " + path + " HTTP " + resp.statusCode() + ": " + resp.body(), /*retryable=*/ true);
        }
        log.info(
                "[okx-rest] {} {} ok: accountId={} sandbox={} bodyLen={}",
                method,
                path,
                account.getId(),
                account.isTestnet(),
                resp.body() == null ? 0 : resp.body().length());
        return parseDataList(resp.body());
    }

    /**
     * 设账户持仓模式为双向(long_short_mode)。OKX 双向持仓 posSide long/short 才可用(createOrder 必填 posSide)。
     *
     * <p>幂等:账户已双向时 OKX 返 code 59000("position mode not modified"),本方法 catch 当成功(已设)不抛。
     * 其他 code 抛 retryable。LiveExecutor 首次 PERP 下单前调一次 + per-account 缓存避免重复调。
     */
    public void setPositionMode(ExchangeAccount account) {
        try {
            fetchPost(account, SET_POSITION_MODE_PATH, "{\"posMode\":\"long_short_mode\"}");
        } catch (ExchangeException e) {
            // 59000 = position mode not modified(账户已双向)→ 幂等,不抛
            if (e.getMessage() != null && e.getMessage().contains("59000")) {
                log.info(
                        "[okx-rest] setPositionMode 59000 (already long_short mode), idempotent: accountId={}",
                        account.getId());
                return;
            }
            throw e;
        }
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
