package com.kwikquant.mcp.interfaces;

import com.kwikquant.market.application.MarketDataService;
import com.kwikquant.market.domain.FundingRate;
import com.kwikquant.market.domain.OrderBook;
import com.kwikquant.mcp.interfaces.view.FundingRateView;
import com.kwikquant.mcp.interfaces.view.KlineView;
import com.kwikquant.mcp.interfaces.view.OrderBookView;
import com.kwikquant.mcp.interfaces.view.TickerView;
import com.kwikquant.shared.infra.McpToolParamInvalidException;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.shared.types.Interval;
import com.kwikquant.shared.types.MarketType;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP 行情数据工具组（§3.3）。4 个 {@code @McpTool}：get_ohlcv / get_ticker / get_orderbook / get_funding_rate。
 *
 * <p>入参全为 String（exchange/marketType/symbol/interval/start/end）便于 Agent 传值，方法内统一用
 * {@link #parseParam(String, Function, String)} 包装枚举/时间解析，非法值抛 {@link McpToolParamInvalidException}
 * (10002)。Exchange/MarketType 现无 fromString，用 {@code valueOf(raw.toUpperCase())} + try-catch（不改枚举 YAGNI）；
 * Interval 用 {@link Interval#fromCcxt(String)}。
 *
 * <p>get_orderbook/get_funding_rate 调 {@link MarketDataService#fetchOrderBook} / {@link
 * MarketDataService#fetchFundingRate}（Wave 10 新增，内部走 CCXT）。PAPER exchange 在 service 层由
 * {@code CcxtExchangeRegistry.getExchange} 抛 IllegalArgumentException，工具层 catch 转 10002（与全局异常处理
 * 对 IllegalArgumentException 的 400 映射对齐，但 MCP 路径需显式抛 McpToolParamInvalidException 携带 10002 语义）。
 * CCXT 限频/网络失败抛 {@code ExchangeException} 透传（6001），不在本层处理。
 *
 * <p>get_funding_rate 仅 PERP：SPOT 调用直接抛 10002（无资金费率语义）。
 */
@Component
public class MarketDataTools {

    private static final int DEFAULT_ORDERBOOK_LIMIT = 20;

    private final MarketDataService marketDataService;

    public MarketDataTools(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @McpTool(
            name = "get_ohlcv",
            description = "获取K线(OHLCV)历史数据。exchange: binance/okx/bitget; marketType: spot/perp; "
                    + "symbol: BTC/USDT 等(含/); interval: 1m/5m/15m/1h/4h/1d; "
                    + "start/end: ISO-8601 瞬时(如 2024-01-01T00:00:00Z)。")
    public List<KlineView> getOhlcv(
            @McpToolParam(description = "交易所: binance/okx/bitget") String exchange,
            @McpToolParam(description = "市场类型: spot/perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol,
            @McpToolParam(description = "K线周期: 1m/5m/15m/1h/4h/1d") String interval,
            @McpToolParam(description = "起始时间 ISO-8601, 如 2024-01-01T00:00:00Z") String start,
            @McpToolParam(description = "结束时间 ISO-8601") String end) {
        Exchange ex = parseExchange(exchange);
        MarketType mt = parseMarketType(marketType);
        Interval iv = parseParam(interval, Interval::fromCcxt, "interval");
        Instant startTime = parseParam(start, Instant::parse, "start");
        Instant endTime = parseParam(end, Instant::parse, "end");
        return marketDataService.getKlineRange(ex, mt, symbol, iv, startTime, endTime).stream()
                .map(KlineView::from)
                .toList();
    }

    @McpTool(
            name = "get_ticker",
            description = "获取最新 ticker(最新价/买一/卖一/24h高低/成交量)。exchange/marketType/symbol 同 get_ohlcv。")
    public TickerView getTicker(
            @McpToolParam(description = "交易所: binance/okx/bitget") String exchange,
            @McpToolParam(description = "市场类型: spot/perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol) {
        Exchange ex = parseExchange(exchange);
        MarketType mt = parseMarketType(marketType);
        return TickerView.from(marketDataService.getLatestTicker(ex, mt, symbol));
    }

    @McpTool(name = "get_orderbook", description = "获取盘口深度(bids/asks 各 N 档)。limit 可省略, 默认 20。exchange=PAPER 抛 10002。")
    public OrderBookView getOrderbook(
            @McpToolParam(description = "交易所: binance/okx/bitget") String exchange,
            @McpToolParam(description = "市场类型: spot/perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol,
            @McpToolParam(description = "档位数, 默认 20", required = false) Integer limit) {
        Exchange ex = parseExchange(exchange);
        MarketType mt = parseMarketType(marketType);
        int lim = limit != null ? limit : DEFAULT_ORDERBOOK_LIMIT;
        try {
            OrderBook ob = marketDataService.fetchOrderBook(ex, mt, symbol, lim);
            if (ob == null) {
                throw new McpToolParamInvalidException("orderbook not available for " + symbol + " on " + exchange);
            }
            return OrderBookView.from(ob);
        } catch (IllegalArgumentException e) {
            // PAPER 或未配置 exchange（CcxtExchangeRegistry 抛）
            throw new McpToolParamInvalidException(e.getMessage());
        }
    }

    @McpTool(name = "get_funding_rate", description = "获取资金费率(仅永续合约 PERP)。SPOT 调用抛 10002。返回当前费率/标记价/下一轮费率及时间。")
    public FundingRateView getFundingRate(
            @McpToolParam(description = "交易所: binance/okx/bitget") String exchange,
            @McpToolParam(description = "市场类型: 须为 perp") String marketType,
            @McpToolParam(description = "交易对, 如 BTC/USDT") String symbol) {
        Exchange ex = parseExchange(exchange);
        MarketType mt = parseMarketType(marketType);
        if (mt != MarketType.PERP) {
            throw new McpToolParamInvalidException("funding rate only available for PERP market, got: " + marketType);
        }
        try {
            FundingRate fr = marketDataService.fetchFundingRate(ex, mt, symbol);
            if (fr == null) {
                throw new McpToolParamInvalidException("funding rate not available for " + symbol + " on " + exchange);
            }
            return FundingRateView.from(fr);
        } catch (IllegalArgumentException e) {
            throw new McpToolParamInvalidException(e.getMessage());
        }
    }

    private static Exchange parseExchange(String raw) {
        return parseParam(raw, s -> Exchange.valueOf(s.toUpperCase()), "exchange");
    }

    private static MarketType parseMarketType(String raw) {
        return parseParam(raw, s -> MarketType.valueOf(s.toUpperCase()), "marketType");
    }

    /**
     * 统一入参解析包装：parser 抛任何 {@link RuntimeException}（IllegalArgumentException /
     * DateTimeParseException / NullPointerException）均转为 {@link McpToolParamInvalidException} (10002)。
     */
    private static <T> T parseParam(String raw, Function<String, T> parser, String desc) {
        try {
            return parser.apply(raw);
        } catch (RuntimeException e) {
            throw new McpToolParamInvalidException("invalid " + desc + ": " + raw);
        }
    }
}
