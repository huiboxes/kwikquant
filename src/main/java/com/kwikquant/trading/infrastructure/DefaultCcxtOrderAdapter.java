package com.kwikquant.trading.infrastructure;

import com.kwikquant.account.application.CcxtAuthExchangeFactory;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.market.application.TradingPairService;
import com.kwikquant.market.domain.TradingPairInfo;
import com.kwikquant.shared.infra.ExchangeException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.shared.types.MarketType;
import com.kwikquant.shared.types.OrderType;
import com.kwikquant.shared.types.PerpMath;
import com.kwikquant.trading.domain.BillRecord;
import com.kwikquant.trading.domain.Order;
import com.kwikquant.trading.domain.OrderAlreadyTerminalException;
import com.kwikquant.trading.domain.PositionSide;
import io.github.ccxt.exchanges.pro.Okx;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default CcxtOrderAdapter 真实实现。实装 createOrder/setLeverage/setMarginMode/cancelOrder
 * (OKX PERP);fetchSnapshot 真实实现(positions+openOrders 对账);subscribeFills 轮询 REST 替代 WS。
 *
 * <p><strong>架构</strong>:策略模式。{@link ExchangeOrderTranslator} 按交易所路由,OKX PERP params
 * 翻译由 {@link OkxOrderTranslator} 纯函数承载(便于单测);DefaultCcxtOrderAdapter 负责副作用——
 * 鉴权 Exchange 构建(经 CcxtAuthExchangeFactory)、symbol 翻译、CCXT API 实际调用、setPositionMode
 * 首次幂等缓存。
 *
 * <p><strong>单位契约(张↔币换算唯一边界)</strong>:域内/DB 规范单位=币数量(base coin);OKX swap 的
 * sz/pos/fillSz 是张数。出站 createOrder 经 {@code PerpMath.toContracts(amount, contractSize)}
 * (除不尽 fail-closed 拒发),回流 fills/positions/openOrders 经 {@code PerpMath.toCoin} 换算后才发布;
 * contractSize/ccxtSymbol 取自 {@link TradingPairService}(市场驱动,装载时已按 type 过滤)。
 * cancelOrder/setLeverage/setMarginMode 不携数量,沿用 {@link OkxOrderTranslator#exchangeSymbol} 的
 * OKX USDT 本位确定性规则,保持撤单/杠杆路径不依赖行情元数据可用性。
 *
 * <p><strong>交易所支持范围</strong>:仅 OKX PERP 真实实装(createOrderWs/cancelOrderWs 强类型
 * 方法 + setLeverage/setMarginMode/setPositionMode 基类 Async .join())。Binance/Bitget PERP 抛
 * {@link ExchangeException}("暂只支持 OKX PERP,单向持仓模式冲突待补齐")。SPOT createOrder 也走 OKX
 * 实装(positionEffect=null → params 空 Map,无 posSide/tdMode,createOrderWs 通用);Binance/Bitget
 * SPOT 同样待补齐。
 *
 * <p><strong>setPositionMode 首次幂等缓存</strong>:OKX 双向持仓模式需首次设置(返 posMode=long_short_mode)。
 * per accountId 缓存,已设则跳过;OKX 对已设同模式返 code=0 不动,首次调失败也标已设避免重复调挂(真错留 sandbox 冒烟处理)。
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
     * per-account 最近已推 fill 的 tradeId(路线 B 轮询去重)。
     *
     * <p>OKX /api/v5/fills 返最近 100 条(按 ts desc),pollFills 每周期拉取后用 tradeId(BigInteger 对比)
     * 过滤已成功处理的成交。首次及重启后也处理，由数据库 external fill ID 唯一约束保证幂等。
     */
    private final ConcurrentMap<Long, BigInteger> lastFillId = new ConcurrentHashMap<>();

    /**
     * per-account 最近已推 bill 的 billId(bills 轮询去重)。
     *
     * <p>OKX /api/v5/account/bills 返最近 100 条(按 ts desc),pollBills 每周期拉取后用 billId(BigInteger 对比)
     * 过滤已成功处理的账单。首次及重启后也处理，由下游数据库 bill/external ID 唯一约束保证幂等。
     * 复用 {@link #parseTradeId} 解析 billId(同为数字字符串)。
     */
    private final ConcurrentMap<Long, BigInteger> lastBillId = new ConcurrentHashMap<>();

    private final CcxtAuthExchangeFactory authExchangeFactory;
    private final OkxOrderTranslator okxTranslator;
    private final OkxRestClient okxRestClient;
    private final OrderMapper orderMapper;
    private final TradingPairService tradingPairService;

    @Autowired
    public DefaultCcxtOrderAdapter(
            CcxtAuthExchangeFactory authExchangeFactory,
            OkxOrderTranslator okxTranslator,
            OkxRestClient okxRestClient,
            OrderMapper orderMapper,
            TradingPairService tradingPairService) {
        this.authExchangeFactory = authExchangeFactory;
        this.okxTranslator = okxTranslator;
        this.okxRestClient = okxRestClient;
        this.orderMapper = orderMapper;
        this.tradingPairService = tradingPairService;
    }

    @Override
    public String createOrder(ExchangeAccount account, Order order) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            throw new ExchangeException("暂只支持 OKX 实盘下单," + ex + " 待补齐(单向持仓模式冲突)", /*retryable=*/ false);
        }
        Okx okx = (Okx) authExchangeFactory.createAuthExchange(account, order.getMarketType());
        // 出站唯一换算点:域内 amount=币数量,OKX swap 的 sz=张数(CCXT 对 swap 把 amount 原样落 sz)。
        // symbol 用 pairInfo.ccxtSymbol 市场驱动翻译(反向合约等任意形态天然正确,不依赖 :quote 后缀硬规则)。
        TradingPairInfo pair = pairFor(account.getExchange(), order.getMarketType(), order.getSymbol());
        String ccxtSymbol = pair.ccxtSymbol();
        String type = ccxtOrderType(order.getOrderType());
        String side = order.getSide().name().toLowerCase();
        Double amount = outboundAmount(order, pair);
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
        } catch (io.github.ccxt.errors.ExchangeError e) {
            log.warn(
                    "[ccxt-adapter] createOrder explicitly rejected: accountId={} symbol={} err={}",
                    account.getId(),
                    ccxtSymbol,
                    e.getMessage());
            throw new ExchangeException("OKX createOrder rejected: " + e.getMessage(), e, /*retryable=*/ false);
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
            throw new ExchangeException("暂只支持 OKX 实盘撤单," + ex + " 待补齐(单向持仓模式冲突)", /*retryable=*/ false);
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
        } catch (io.github.ccxt.errors.OrderNotFound e) {
            // OKX 51400:订单已成交/已撤销/不存在 → cancel 语义已达成。抛 domain exception,
            // LiveExecutor 据此 confirmCancelled(订单实际已撤销),而非卡 PENDING_CANCEL。
            log.info(
                    "[ccxt-adapter] cancelOrder: order not found on exchange (already terminal): accountId={} exchangeOrderId={}",
                    account.getId(),
                    exchangeOrderId);
            throw new OrderAlreadyTerminalException("OKX order already terminal: " + e.getMessage(), e);
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
            throw new ExchangeException("暂只支持 OKX setLeverage," + ex + " 待补齐(单向持仓模式冲突)", /*retryable=*/ false);
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
            throw new ExchangeException("暂只支持 OKX setMarginMode," + ex + " 待补齐(单向持仓模式冲突)", /*retryable=*/ false);
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
    public void setPositionMode(ExchangeAccount account) {
        if (account.getExchange() != Exchange.OKX) {
            throw new ExchangeException(
                    "暂只支持 OKX setPositionMode," + account.getExchange() + " 待补齐(单向持仓模式冲突)", /*retryable=*/ false);
        }
        okxRestClient.setPositionMode(account);
    }

    @Override
    public AccountSnapshot fetchSnapshot(ExchangeAccount account) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            log.warn("[ccxt-adapter] fetchSnapshot 仅 OKX 实装,{} 暂返空: accountId={}", ex, account.getId());
            return new AccountSnapshot(List.of(), List.of());
        }
        // OkxRestClient 直调 OKX REST /api/v5/account/positions(绕 CCXT fetchPositions bug)
        // → raw list → OkxOrderTranslator.parsePositionsRest 纯函数解析为 PositionSnapshot。
        // fetchOpenOrders 暂未实现(需 OKX /api/v5/trade/orders-pending,经 OkxRestClient 扩 GET)。
        List<Map<String, Object>> rawPositions;
        try {
            rawPositions = okxRestClient.fetchPositions(account);
        } catch (RuntimeException e) {
            log.error(
                    "[ccxt-adapter] fetchSnapshot fetchPositions failed: accountId={} err={}",
                    account.getId(),
                    e.getMessage(),
                    e);
            throw new ExchangeException("OKX fetchPositions failed: " + e.getMessage(), e, /*retryable=*/ true);
        }
        // 张→币:OKX pos/sz 是张数,对账消费方(PositionReconcileScheduler/LiveExecutor)直接与本地
        // 币语义 qty 比较,换算必须在本边界完成。pair 元数据加载失败 → 异常透传(调用方 catch 计 fetchFail)。
        List<PositionSnapshot> positions =
                toCoinPositions(account.getExchange(), okxTranslator.parsePositionsRest(rawPositions));
        // fetchOpenOrders:对账挂单(发现本地无记录的挂单,如 user 在 OKX 页面手动下单 + 重启间)。
        List<OrderSnapshot> openOrders;
        try {
            openOrders = toCoinOrders(
                    account.getExchange(), okxTranslator.parseOpenOrdersRest(okxRestClient.fetchOpenOrders(account)));
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
    public OrderSnapshot fetchOrder(ExchangeAccount account, Order order) {
        if (account.getExchange() != Exchange.OKX) {
            throw new ExchangeException("暂只支持 OKX 实盘订单查询", /*retryable=*/ false);
        }
        String clOrdId = OkxOrderTranslator.clientOrderId(order);
        String instId = OkxOrderTranslator.instrumentId(order.getSymbol(), order.getMarketType());
        try {
            List<OrderSnapshot> orders =
                    okxTranslator.parseOpenOrdersRest(okxRestClient.fetchOrder(account, instId, clOrdId));
            if (orders.isEmpty()) {
                return null;
            }
            OrderSnapshot s = orders.get(0);
            if (order.getMarketType() != MarketType.PERP) {
                return s; // SPOT sz 本身就是币数量
            }
            // 本系统管理的 PERP 单:张→币后返回(fillsSettled 直接与本地 filledQty(币)比较)。
            // pair 规格拿不到 → 抛异常(reconcileOrder catch 后下轮重试),不按张数发布污染对账。
            TradingPairInfo pair = pairFor(account.getExchange(), MarketType.PERP, order.getSymbol());
            return new OrderSnapshot(
                    s.exchangeOrderId(),
                    s.clientOrderId(),
                    s.symbol(),
                    s.side(),
                    s.amount() == null ? null : PerpMath.toCoin(s.amount(), pair.contractSize()),
                    s.filledQty() == null ? null : PerpMath.toCoin(s.filledQty(), pair.contractSize()),
                    s.status());
        } catch (ExchangeException e) {
            if (e.getMessage() != null && e.getMessage().contains("51603")) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public Runnable subscribeFills(ExchangeAccount account, EventHandler<FillEvent> handler) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            log.warn("[ccxt-adapter] subscribeFills 仅 OKX 实装,{} 暂返 no-op: accountId={}", ex, account.getId());
            return () -> {};
        }
        // 路线 B:轮询 REST 替代 WS(CCTX Java 私有 WS watch* 全 NotSupported,spike 验证)。
        // ScheduledExecutorService daemon 线程 5s 周期 pollFills,unsubscribe shutdownNow。
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "okx-fills-poller-" + account.getId());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> pollFills(account, handler), 0, 5, TimeUnit.SECONDS);
        log.info("[ccxt-adapter] subscribeFills 轮询启动: accountId={} interval=5s", account.getId());
        return () -> {
            scheduler.shutdownNow();
            log.info("[ccxt-adapter] subscribeFills 轮询停止: accountId={}", account.getId());
        };
    }

    @Override
    public Runnable subscribeBills(ExchangeAccount account, EventHandler<BillRecord> handler) {
        Exchange ex = account.getExchange();
        if (ex != Exchange.OKX) {
            log.warn("[ccxt-adapter] subscribeBills 仅 OKX 实装,{} 暂返 no-op: accountId={}", ex, account.getId());
            return () -> {};
        }
        // 仿 subscribeFills:5s REST 轮询替代 WS(OKX 私有 WS watch* 全 NotSupported)。
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "okx-bills-poller-" + account.getId());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> pollBills(account, handler), 0, 5, TimeUnit.SECONDS);
        log.info("[ccxt-adapter] subscribeBills 轮询启动: accountId={} interval=5s", account.getId());
        return () -> {
            scheduler.shutdownNow();
            log.info("[ccxt-adapter] subscribeBills 轮询停止: accountId={}", account.getId());
        };
    }

    /**
     * 周期拉 OKX bills + 解析 + 去重(last seen billId)+ 按 type 分流推 consumer。
     *
     * <p>OKX bills 返 desc(最新在前),pollBills 反转升序处理,从 first 起跳 <= lastSeen,
     * 逐条处理 > lastSeen 的 bill，只有回调确认成功才推进游标。首次及重启也处理，数据库唯一约束去重。
     * fetchBills/回调失败均保留当前游标供下次重试。
     *
     * <p>分流由 consumer(LiveExecutor 注入)处理:type=5/9 → LiquidationService,
     * type=8 → FundingSettlementService,其他 type 忽略。本方法只管去重 + 推送,不碰 application 层 service。
     */
    void pollBills(ExchangeAccount account, EventHandler<BillRecord> handler) {
        List<Map<String, Object>> raw;
        try {
            raw = okxRestClient.fetchBills(account, null);
        } catch (RuntimeException e) {
            log.warn(
                    "[ccxt-adapter] pollBills fetchBills failed: accountId={} err={}", account.getId(), e.getMessage());
            return;
        }
        if (raw.isEmpty()) {
            return;
        }
        List<BillRecord> bills = okxTranslator.parseBills(raw, account.getId());
        // raw desc(最新在前)→ 反转升序(旧→新)便于 lastSeen 顺序过滤
        java.util.Collections.reverse(bills);
        BigInteger lastSeen = lastBillId.get(account.getId());
        for (BillRecord b : bills) {
            BigInteger billId = parseTradeId(b.billId()); // 复用, billId 也是数字字符串
            if (billId == null) {
                continue;
            }
            if (lastSeen != null && billId.compareTo(lastSeen) <= 0) {
                continue; // 已推或更旧,跳过
            }
            try {
                if (!handler.handle(b)) {
                    log.warn(
                            "[ccxt-adapter] pollBills handler did not confirm processing: accountId={} billId={}",
                            account.getId(),
                            b.billId());
                    return;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "[ccxt-adapter] pollBills handler failed: accountId={} billId={} err={}",
                        account.getId(),
                        b.billId(),
                        e.getMessage());
                return;
            }
            lastBillId.put(account.getId(), billId);
            lastSeen = billId;
        }
    }

    /**
     * 周期拉 OKX fills + 解析 + 去重(last seen tradeId)+ 查 OrderMapper 填 orderId + 推 consumer。
     *
     * <p>OKX fills 返 desc(最新在前),pollFills 反转升序处理(旧→新),从 first 起跳 <= lastSeen,
     * 逐条处理 > lastSeen 的 fill，只有回调确认成功才推进游标。首次及重启也处理，数据库 external fill ID
     * 唯一约束去重。fetchFills/回调失败均保留当前游标供下次重试。
     */
    void pollFills(ExchangeAccount account, EventHandler<FillEvent> handler) {
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
        for (CcxtOrderAdapter.FillEvent f : fills) {
            BigInteger tradeId = parseTradeId(f.externalFillId());
            if (tradeId == null) {
                continue;
            }
            if (lastSeen != null && tradeId.compareTo(lastSeen) <= 0) {
                continue; // 已推或更旧,跳过
            }
            Order local = resolveLocalOrder(account.getId(), f);
            if (local == null) {
                // 成交无法归属到本系统管理的订单(典型:用户在交易所页面手工下单)。此类成交没有
                // 本地订单可应用,若阻塞游标会永久卡死该账户全部成交流水线 → 记审计日志后推进游标。
                // PENDING_NEW 订单不在此列:其成交的 clOrdId 带 KQ 前缀,resolveLocalOrder 已反解归属。
                log.warn(
                        "[ccxt-adapter] pollFills skipping fill of unmanaged order: accountId={} tradeId={}"
                                + " exchangeOrderId={} clOrdId={} action=manual-review",
                        account.getId(),
                        f.externalFillId(),
                        f.exchangeOrderId(),
                        f.clientOrderId());
                lastFillId.put(account.getId(), tradeId);
                lastSeen = tradeId;
                continue;
            }
            // 张→币换算依赖 pair 规格:加载失败/规格缺失时保留游标下轮重试,
            // 绝不按张数发布(下游 applyPerpDelta 钱数学全币语义,单位错=错 ctVal 倍)。
            BigDecimal coinQty = f.qty();
            if (coinQty != null && local.getMarketType() == MarketType.PERP) {
                TradingPairInfo pair;
                try {
                    pair = pairOrNull(account.getExchange(), MarketType.PERP, local.getSymbol());
                } catch (RuntimeException e) {
                    log.warn(
                            "[ccxt-adapter] pollFills pair spec lookup failed; retaining cursor for retry:"
                                    + " accountId={} tradeId={} symbol={} err={}",
                            account.getId(),
                            f.externalFillId(),
                            local.getSymbol(),
                            e.getMessage());
                    return;
                }
                if (pair == null) {
                    // 受管成交查不到 pair 规格:暂态(元数据加载异常)会自愈;永久形态(退市/白名单变更/
                    // PERP 条目缺 contractSize 被装载跳过)会卡住该账户 fills 流水线——这是刻意取舍:
                    // 跳过=永久丢一笔钱事件(持仓/余额静默漂移),卡住=可恢复且每轮报错可见。ERROR 级供告警。
                    log.error(
                            "[ccxt-adapter] pollFills no pair spec for managed PERP fill; retaining cursor"
                                    + " (fills pipeline stalled until pair metadata recovers):"
                                    + " accountId={} tradeId={} symbol={} action=manual-review",
                            account.getId(),
                            f.externalFillId(),
                            local.getSymbol());
                    return;
                }
                coinQty = PerpMath.toCoin(coinQty, pair.contractSize());
            }
            FillEvent event = new CcxtOrderAdapter.FillEvent(
                    local.getId(),
                    f.exchangeOrderId(),
                    f.clientOrderId(),
                    f.externalFillId(),
                    f.price(),
                    coinQty,
                    f.fee(),
                    f.feeCurrency(),
                    f.liquidity(),
                    f.filledAt());
            try {
                if (!handler.handle(event)) {
                    log.warn(
                            "[ccxt-adapter] pollFills handler did not confirm processing: accountId={} tradeId={}",
                            account.getId(),
                            f.externalFillId());
                    return;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "[ccxt-adapter] pollFills handler failed: accountId={} tradeId={} err={}",
                        account.getId(),
                        f.externalFillId(),
                        e.getMessage());
                return;
            }
            lastFillId.put(account.getId(), tradeId);
            lastSeen = tradeId;
        }
    }

    /**
     * 成交归属反查:先按 exchangeOrderId(常规路径);未命中再按 KQ clOrdId 反解订单 ID
     * (覆盖 PENDING_NEW 订单 exchangeOrderId 尚未落库的窗口)。返 null 表示成交不属于本系统
     * 管理的订单(交易所手工单等),由调用方决定跳过。反解后校验 accountId + clOrdId 重建一致,
     * 防止 clOrdId 伪造或碰撞误归属。
     */
    private Order resolveLocalOrder(long accountId, CcxtOrderAdapter.FillEvent f) {
        Order local = orderMapper.findByExchangeOrderId(accountId, f.exchangeOrderId());
        if (local != null) {
            return local;
        }
        Long derivedId = OkxOrderTranslator.orderIdFromClientOrderId(f.clientOrderId());
        if (derivedId == null) {
            return null;
        }
        Order candidate = orderMapper.findById(derivedId);
        if (candidate != null
                && candidate.getAccountId() == accountId
                && OkxOrderTranslator.clientOrderId(candidate).equals(f.clientOrderId())) {
            return candidate;
        }
        return null;
    }

    // ── 张↔币换算边界(域内规范单位=币,OKX sz/pos/fillSz=张;换算只允许发生在本类)──

    /**
     * 域内币数量 → 出站请求数量:PERP 经 contractSize 换算张数(除不尽 fail-closed 拒发,
     * 静默取整会让实际下单量偏离意图);SPOT 原样。
     */
    private static Double outboundAmount(Order order, TradingPairInfo pair) {
        BigDecimal amount = order.getAmount();
        if (order.getMarketType() == MarketType.PERP) {
            try {
                amount = PerpMath.toContracts(amount, pair.contractSize());
            } catch (ArithmeticException e) {
                throw new ExchangeException(
                        "PERP amount " + order.getAmount() + " not expressible in whole contracts (contractSize="
                                + pair.contractSize() + ")",
                        e,
                        /*retryable=*/ false);
            }
        }
        return amount.doubleValue();
    }

    /**
     * PositionSnapshot 列表张→币。symbol 不在 pair 表(典型:用户在交易所页面手动开的仓,
     * 超出支持范围)→ skip 该条 + warn,不阻塞其余持仓对账;pair 元数据加载失败异常透传(调用方计 fetchFail)。
     */
    private List<PositionSnapshot> toCoinPositions(Exchange ex, List<PositionSnapshot> parsed) {
        List<PositionSnapshot> out = new ArrayList<>(parsed.size());
        for (PositionSnapshot s : parsed) {
            TradingPairInfo pair = pairOrNull(ex, MarketType.PERP, s.symbol());
            if (pair == null) {
                log.warn("[ccxt-adapter] fetchSnapshot skip position without pair spec: symbol={}", s.symbol());
                continue;
            }
            BigDecimal cs = pair.contractSize();
            out.add(new PositionSnapshot(
                    s.symbol(),
                    s.side(),
                    s.qty() == null ? null : PerpMath.toCoin(s.qty(), cs),
                    s.entryPrice(),
                    s.marketType(),
                    s.positionSide(),
                    s.leverage(),
                    s.marginMode(),
                    s.liquidationPrice(),
                    s.markPrice(),
                    s.maintMarginRate(),
                    s.unrealizedPnl()));
        }
        return out;
    }

    /** OrderSnapshot 列表张→币。symbol 无 pair 规格(交易所手工单)→ skip + warn,同 toCoinPositions。 */
    private List<OrderSnapshot> toCoinOrders(Exchange ex, List<OrderSnapshot> parsed) {
        List<OrderSnapshot> out = new ArrayList<>(parsed.size());
        for (OrderSnapshot s : parsed) {
            TradingPairInfo pair = pairOrNull(ex, MarketType.PERP, s.symbol());
            if (pair == null) {
                log.warn("[ccxt-adapter] fetchSnapshot skip open order without pair spec: symbol={}", s.symbol());
                continue;
            }
            BigDecimal cs = pair.contractSize();
            out.add(new OrderSnapshot(
                    s.exchangeOrderId(),
                    s.clientOrderId(),
                    s.symbol(),
                    s.side(),
                    s.amount() == null ? null : PerpMath.toCoin(s.amount(), cs),
                    s.filledQty() == null ? null : PerpMath.toCoin(s.filledQty(), cs),
                    s.status()));
        }
        return out;
    }

    /** pair 规格查询(canonical 等值匹配);pair 表无此 symbol 返 null,由调用方定 fail-closed 策略。 */
    private TradingPairInfo pairOrNull(Exchange ex, MarketType marketType, String canonical) {
        return tradingPairService.getPairs(ex, marketType).stream()
                .filter(p -> p.symbol().equals(canonical))
                .findFirst()
                .orElse(null);
    }

    /** 同 {@link #pairOrNull},缺失即抛 non-retryable(受管订单的 symbol 必在 pair 表,缺失=规格世界已变)。 */
    private TradingPairInfo pairFor(Exchange ex, MarketType marketType, String canonical) {
        TradingPairInfo pair = pairOrNull(ex, marketType, canonical);
        if (pair == null) {
            throw new ExchangeException(
                    "pair spec unavailable for " + ex + " " + marketType + " " + canonical, /*retryable=*/ false);
        }
        return pair;
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
     * 真实异常(51000 等模式冲突,需 user 在页面手动设)留 sandbox 冒烟处理。
     */
    private void ensurePositionMode(Long accountId, Okx okx, String ccxtSymbol) {
        if (positionModeSet.containsKey(accountId)) {
            return;
        }
        try {
            okx.setPositionMode(true, Map.of("symbol", ccxtSymbol)).join();
            log.info("[ccxt-adapter] setPositionMode(long_short_mode) ok: accountId={}", accountId);
        } catch (CompletionException e) {
            // OKX 已设同模式返 code=0 不动,仍标已设避免重复调;真错(51000 等需 user 页面设)留 sandbox 冒烟处理
            log.warn(
                    "[ccxt-adapter] setPositionMode returned (assumed already set or error): accountId={} err={}",
                    accountId,
                    e.getMessage());
        }
        positionModeSet.put(accountId, Boolean.TRUE);
    }

    /** OrderType → CCXT type 字符串("market"/"limit")。条件单(SP-TP-TSL)不支持,抛 non-retryable。 */
    private static String ccxtOrderType(OrderType type) {
        return switch (type) {
            case MARKET -> "market";
            case LIMIT -> "limit";
            default -> throw new ExchangeException(
                    "暂不支持条件单实盘下单,type=" + type + " (spike S1/4b 验证)", /*retryable=*/ false);
        };
    }
}
