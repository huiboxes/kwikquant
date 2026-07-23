package com.kwikquant.trading.infrastructure;

import com.kwikquant.account.application.CcxtAuthExchangeFactory;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.PositionSide;
import io.github.ccxt.exchanges.pro.Okx;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default CcxtOrderAdapter 真实实现。阶段4a.3 实装 createOrder/setLeverage/setMarginMode/cancelOrder
 * (OKX PERP);fetchSnapshot 4a.4+4b 真实实现(positions+openOrders 对账);subscribeFills 4b 轮询 REST 替代 WS。
 *
 * <p><strong>架构</strong>:策略模式。{@link ExchangeOrderTranslator} 按交易所路由,OKX PERP params
 * 翻译由 {@link OkxOrderTranslator} 纯函数承载(便于单测);DefaultCcxtOrderAdapter 负责副作用——
 * 鉴权 Exchange 构建(经 CcxtAuthExchangeFactory)、symbol 翻译(经 OkxOrderTranslator,4a.2 去 CcxtExchangeRegistry 因模块边界)、
 * CCXT API 实际调用、setPositionMode 首次幂等缓存。
 *
 * <p><strong>交易所支持范围(阶段4a.3)</strong>:仅 OKX PERP 真实实装(createOrderWs/cancelOrderWs 强类型
 * 方法 + setLeverage/setMarginMode/setPositionMode 基类 Async .join())。Binance/Bitget PERP 抛
 * {@link ExchangeException}("暂只支持 OKX PERP,§10 B7 单向持仓模式冲突留账")。SPOT createOrder 也走 OKX
 * 实装(positionEffect=null → params 空 Map,无 posSide/tdMode,createOrderWs 通用);Binance/Bitget
 * SPOT 同样留账。
 *
 * <p><strong>setPositionMode 首次幂等缓存</strong>:OKX 双向持仓模式需首次设置(返 posMode=long_short_mode)。
 * per accountId 缓存,已设则跳过;OKX 对已设同模式返 code=0 不动,首次调失败也标已设避免重复调挂(真错 4b 处理)。
 *
 * <p><strong>异常处理</strong>:CCXT 调用失败包装为 {@link ExchangeException}(retryable=true,网络/限频可重试),
 * 保留 cause 便于排障;Binance/Bitget/未支持的 MarketType 抛 non-retryable(配置/合约边界,重试无用)。
 *
 * <p>JaCoCo 已排除本类(外部 API 不可单测);单测通过 mock CcxtAuthExchangeFactory 返 mock Okx
 * verify params 翻译正确性,不调真实 API。
 */
@Component
@ConditionalOnMissingBean(name = "ccxtOrderAdapter")
public class DefaultCcxtOrderAdapter implements CcxtOrderAdapter {

    private static final Logger log = LoggerFactory.getLogger(DefaultCcxtOrderAdapter.class);

    /** OKX 双向持仓模式首次设置幂等缓存(accountId → 已设 true)。避免每单重复调 setPositionMode。 */
    private final ConcurrentMap<Long, Boolean> positionModeSet = new ConcurrentHashMap<>();

    /**
     * per-account 最近已推 fill 的 tradeId(4b 路线 B 轮询去重)。
     *
     * <p>OKX /api/v5/fills 返最近 100 条(按 ts desc),pollFills 每周期拉取后用 tradeId(BigInteger 对比)
     * 过滤已推,只推 > lastSeen 的新成交。首次 lastSeen=null,拉一次记最大 tradeId 不推(避免重放历史)。
     */
    private final ConcurrentMap<Long, BigInteger> lastFillId = new ConcurrentHashMap<>();

    private final CcxtAuthExchangeFactory authExchangeFactory;
    private final OkxOrderTranslator okxTranslator;
    private final OkxRestClient okxRestClient;
    private final OrderMapper orderMapper;

    @Autowired
    public DefaultCcxtOrderAdapter(
            CcxtAuthExchangeFactory authExchangeFactory,
            OkxOrderTranslator okxTranslator,
            OkxRestClient okxRestClient,
            OrderMapper orderMapper) {
        this.authExchangeFactory = authExchangeFactory;
        this.okxTranslator = okxTranslator;
        this.okxRestClient = okxRestClient;
        this.orderMapper = orderMapper;
    }

    @Override
    public String createOrder(ExchangeAccount account, Order order) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            throw new ExchangeException("暂只支持 OKX 实盘下单," + ex + " 留账 §10 B7(单向持仓模式冲突)", /*retryable=*/ false);
        }
        Okx okx = (Okx) authExchangeFactory.createAuthExchange(account, order.getMarketType());
        String ccxtSymbol = okxTranslator.exchangeSymbol(order.getSymbol(), order.getMarketType());
        String type = ccxtOrderType(order.getOrderType());
        String side = order.getSide().name().toLowerCase();
        Double amount = order.getAmount().doubleValue();
        Double price = order.getPrice() != null ? order.getPrice().doubleValue() : null;
        Map<String, Object> params = okxTranslator.createOrderParams(order);

        // 首次 per account 调 setPositionMode(OKX 双向持仓,幂等)
        ensurePositionMode(account.getId(), okx, ccxtSymbol);

        log.info(
                "[ccxt-adapter] createOrder: accountId={} symbol={} type={} side={} amount={} price={} params={}",
                account.getId(),
                ccxtSymbol,
                type,
                side,
                amount,
                price,
                params);
        io.github.ccxt.types.Order ccxtOrder;
        try {
            ccxtOrder = okx.createOrderWs(ccxtSymbol, type, side, amount, price, params);
        } catch (RuntimeException e) {
            log.error(
                    "[ccxt-adapter] createOrder failed: accountId={} symbol={} err={}",
                    account.getId(),
                    ccxtSymbol,
                    e.getMessage(),
                    e);
            throw new ExchangeException("OKX createOrder failed: " + e.getMessage(), e, /*retryable=*/ true);
        }
        String exchangeOrderId = ccxtOrder.id;
        if (exchangeOrderId == null || exchangeOrderId.isBlank()) {
            throw new ExchangeException(
                    "OKX createOrder returned null/blank id (params=" + params + ")", /*retryable=*/ true);
        }
        log.info("[ccxt-adapter] createOrder ok: accountId={} exchangeOrderId={}", account.getId(), exchangeOrderId);
        return exchangeOrderId;
    }

    @Override
    public void cancelOrder(ExchangeAccount account, Order order) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            throw new ExchangeException("暂只支持 OKX 实盘撤单," + ex + " 留账 §10 B7", /*retryable=*/ false);
        }
        Okx okx = (Okx) authExchangeFactory.createAuthExchange(account, order.getMarketType());
        String ccxtSymbol = okxTranslator.exchangeSymbol(order.getSymbol(), order.getMarketType());
        String exchangeOrderId = order.getExchangeOrderId();
        if (exchangeOrderId == null || exchangeOrderId.isBlank()) {
            throw new ExchangeException(
                    "cancelOrder: order has no exchangeOrderId (orderId=" + order.getId() + ")", /*retryable=*/ false);
        }
        log.info(
                "[ccxt-adapter] cancelOrder: accountId={} exchangeOrderId={} symbol={}",
                account.getId(),
                exchangeOrderId,
                ccxtSymbol);
        try {
            okx.cancelOrderWs(exchangeOrderId, ccxtSymbol, Map.of());
        } catch (RuntimeException e) {
            log.error(
                    "[ccxt-adapter] cancelOrder failed: accountId={} exchangeOrderId={} err={}",
                    account.getId(),
                    exchangeOrderId,
                    e.getMessage(),
                    e);
            throw new ExchangeException("OKX cancelOrder failed: " + e.getMessage(), e, /*retryable=*/ true);
        }
    }

    @Override
    public void setLeverage(
            ExchangeAccount account,
            String canonicalSymbol,
            MarketType marketType,
            int leverage,
            MarginMode mode,
            PositionSide posSide) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            throw new ExchangeException("暂只支持 OKX setLeverage," + ex + " 留账 §10 B7", /*retryable=*/ false);
        }
        var ccxtExchange = authExchangeFactory.createAuthExchange(account, marketType);
        String ccxtSymbol = okxTranslator.exchangeSymbol(canonicalSymbol, marketType);
        Map<String, Object> params = okxTranslator.setLeverageParams(mode, posSide);
        log.info(
                "[ccxt-adapter] setLeverage: accountId={} symbol={}→{} lev={} mode={} posSide={} params={}",
                account.getId(),
                canonicalSymbol,
                ccxtSymbol,
                leverage,
                mode,
                posSide,
                params);
        try {
            ccxtExchange.setLeverage(leverage, ccxtSymbol, params).join();
        } catch (CompletionException e) {
            throw new ExchangeException("OKX setLeverage failed: " + e.getMessage(), e, /*retryable=*/ true);
        }
    }

    @Override
    public void setMarginMode(
            ExchangeAccount account,
            String canonicalSymbol,
            MarketType marketType,
            MarginMode mode,
            int leverage,
            PositionSide posSide) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            throw new ExchangeException("暂只支持 OKX setMarginMode," + ex + " 留账 §10 B7", /*retryable=*/ false);
        }
        var ccxtExchange = authExchangeFactory.createAuthExchange(account, marketType);
        String ccxtSymbol = okxTranslator.exchangeSymbol(canonicalSymbol, marketType);
        Map<String, Object> params = okxTranslator.setMarginModeParams(leverage, posSide);
        String tdMode = mode.name().toLowerCase();
        log.info(
                "[ccxt-adapter] setMarginMode: accountId={} symbol={}→{} mode={} lev={} params={}",
                account.getId(),
                canonicalSymbol,
                ccxtSymbol,
                mode,
                leverage,
                params);
        try {
            ccxtExchange.setMarginMode(tdMode, ccxtSymbol, params).join();
        } catch (CompletionException e) {
            throw new ExchangeException("OKX setMarginMode failed: " + e.getMessage(), e, /*retryable=*/ true);
        }
    }

    @Override
    public AccountSnapshot fetchSnapshot(ExchangeAccount account) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            log.warn("[ccxt-adapter] fetchSnapshot 仅 OKX 实装,{} 留账返空: accountId={}", ex, account.getId());
            return new AccountSnapshot(List.of(), List.of());
        }
        // 4a.4 真实实现:OkxRestClient 直调 OKX REST /api/v5/account/positions(绕 CCXT fetchPositions bug)
        // → raw list → OkxOrderTranslator.parsePositionsRest 纯函数解析为 PositionSnapshot。
        // fetchOpenOrders 留账(4b,需 OKX /api/v5/trade/orders-pending,经 OkxRestClient 扩 GET)。
        List<Map<String, Object>> rawPositions;
        try {
            rawPositions = okxRestClient.fetchPositions(account);
        } catch (RuntimeException e) {
            log.error(
                    "[ccxt-adapter] fetchSnapshot fetchPositions failed: accountId={} err={}",
                    account.getId(),
                    e.getMessage(),
                    e);
            // 对账失败不阻塞 startup(LiveExecutor 已 try/catch 包裹),返空快照让流程继续;
            // 真实异常由上层 startupSnapshot catch 记审计人工介入。spike 验证后此处可重抛。
            return new AccountSnapshot(List.of(), List.of());
        }
        List<PositionSnapshot> positions = okxTranslator.parsePositionsRest(rawPositions);
        // 4b fetchOpenOrders:对账挂单(发现本地无记录的挂单,如 user 在 OKX 页面手动下单 + 重启间)。
        List<OrderSnapshot> openOrders;
        try {
            openOrders = okxTranslator.parseOpenOrdersRest(okxRestClient.fetchOpenOrders(account));
        } catch (RuntimeException e) {
            log.warn(
                    "[ccxt-adapter] fetchSnapshot fetchOpenOrders failed: accountId={} err={}",
                    account.getId(),
                    e.getMessage());
            openOrders = List.of(); // 挂单拉失败不阻塞 positions 对账
        }
        log.info(
                "[ccxt-adapter] fetchSnapshot ok: accountId={} openOrders={} positions={}",
                account.getId(),
                openOrders.size(),
                positions.size());
        return new AccountSnapshot(openOrders, positions);
    }

    @Override
    public Runnable subscribeFills(ExchangeAccount account, Consumer<FillEvent> consumer) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            log.warn("[ccxt-adapter] subscribeFills 仅 OKX 实装,{} 留账返 no-op: accountId={}", ex, account.getId());
            return () -> {};
        }
        // 4b 路线 B:轮询 REST 替代 WS(CCTX Java 私有 WS watch* 全 NotSupported,spike 验证)。
        // ScheduledExecutorService daemon 线程 5s 周期 pollFills,unsubscribe shutdownNow。
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "okx-fills-poller-" + account.getId());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> pollFills(account, consumer), 0, 5, TimeUnit.SECONDS);
        log.info("[ccxt-adapter] subscribeFills 轮询启动: accountId={} interval=5s", account.getId());
        return () -> {
            scheduler.shutdownNow();
            log.info("[ccxt-adapter] subscribeFills 轮询停止: accountId={}", account.getId());
        };
    }

    /**
     * 周期拉 OKX fills + 解析 + 去重(last seen tradeId)+ 查 OrderMapper 填 orderId + 推 consumer。
     *
     * <p>OKX fills 返 desc(最新在前),pollFills 反转升序处理(旧→新),从 first 起跳 <= lastSeen,
     * 推 > lastSeen 并更新 lastSeen=最后推的 tradeId。首次(lastSeen=null)拉一次记 max tradeId 不推
     * (避免重放历史已处理成交)。fetchFills 失败 log warn 不抛(轮询容错,下次重试)。
     */
    void pollFills(ExchangeAccount account, Consumer<FillEvent> consumer) {
        List<Map<String, Object>> raw;
        try {
            raw = okxRestClient.fetchFills(account);
        } catch (RuntimeException e) {
            log.warn(
                    "[ccxt-adapter] pollFills fetchFills failed: accountId={} err={}", account.getId(), e.getMessage());
            return;
        }
        if (raw.isEmpty()) {
            return;
        }
        List<CcxtOrderAdapter.FillEvent> fills = okxTranslator.parseFillsRest(raw);
        // raw desc(最新在前)→ 反转升序(旧→新)便于 lastSeen 顺序过滤
        java.util.Collections.reverse(fills);
        BigInteger lastSeen = lastFillId.get(account.getId());
        boolean firstPoll = lastSeen == null; // 首次只记 max tradeId 不推(避免重放历史已处理成交)
        BigInteger maxSeen = lastSeen;
        for (CcxtOrderAdapter.FillEvent f : fills) {
            BigInteger tradeId = parseTradeId(f.externalFillId());
            if (tradeId == null) {
                continue;
            }
            if (lastSeen != null && tradeId.compareTo(lastSeen) <= 0) {
                continue; // 已推或更旧,跳过
            }
            if (!firstPoll) {
                // 查 OrderMapper 填 orderId(exchangeOrderId → 本地 order id)
                Order local = orderMapper.findByExchangeOrderId(account.getId(), f.exchangeOrderId());
                long orderId = local != null ? local.getId() : 0L;
                consumer.accept(new CcxtOrderAdapter.FillEvent(
                        orderId,
                        f.exchangeOrderId(),
                        f.externalFillId(),
                        f.price(),
                        f.qty(),
                        f.fee(),
                        f.feeCurrency(),
                        f.liquidity(),
                        f.filledAt()));
            }
            if (maxSeen == null || tradeId.compareTo(maxSeen) > 0) {
                maxSeen = tradeId;
            }
        }
        if (maxSeen != null) {
            lastFillId.put(account.getId(), maxSeen);
        }
    }

    /** OKX tradeId(数字字符串)→ BigInteger。null/非数字返 null。 */
    private static BigInteger parseTradeId(String tradeId) {
        if (tradeId == null || tradeId.isBlank()) {
            return null;
        }
        try {
            return new BigInteger(tradeId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 首次 per account 调 OKX setPositionMode(双向持仓 long_short_mode),幂等缓存避免重复调。
     *
     * <p>OKX 对已设同模式返 code=0 不动,故即使首次调因已设而"失败",也标已设避免后续每单重试。
     * 真实异常(51000 等模式冲突,需 user 在页面手动设)留 4b sandbox 冒烟处理。
     */
    private void ensurePositionMode(Long accountId, Okx okx, String ccxtSymbol) {
        if (positionModeSet.containsKey(accountId)) {
            return;
        }
        try {
            okx.setPositionMode(true, Map.of("symbol", ccxtSymbol)).join();
            log.info("[ccxt-adapter] setPositionMode(long_short_mode) ok: accountId={}", accountId);
        } catch (CompletionException e) {
            // OKX 已设同模式返 code=0 不动,仍标已设避免重复调;真错(51000 等需 user 页面设)4b 处理
            log.warn(
                    "[ccxt-adapter] setPositionMode returned (assumed already set or error): accountId={} err={}",
                    accountId,
                    e.getMessage());
        }
        positionModeSet.put(accountId, Boolean.TRUE);
    }

    /** OrderType → CCXT type 字符串("market"/"limit")。条件单(SP-TP-TSL)4a.3 不支持,抛 non-retryable。 */
    private static String ccxtOrderType(OrderType type) {
        return switch (type) {
            case MARKET -> "market";
            case LIMIT -> "limit";
            default -> throw new ExchangeException(
                    "暂不支持条件单实盘下单,type=" + type + " (spike S1/4b 验证)", /*retryable=*/ false);
        };
    }
}
