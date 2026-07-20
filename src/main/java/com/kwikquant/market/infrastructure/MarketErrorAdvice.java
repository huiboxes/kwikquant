package com.kwikquant.market.infrastructure;

import com.kwikquant.market.domain.MarketDataException;
import com.kwikquant.market.domain.SymbolNotListedException;
import com.kwikquant.shared.infra.ApiResponse;
import com.kwikquant.shared.infra.ErrorCode;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.kwikquant.market")
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class MarketErrorAdvice {

    @ExceptionHandler(MarketDataException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    ApiResponse<Void> handleMarketData(MarketDataException e) {
        return ApiResponse.error(ErrorCode.EXCHANGE_UNAVAILABLE, "market data unavailable", MDC.get("traceId"));
    }

    /**
     * 请求的 symbol 在该 (交易所, 市场类型) 的 markets 表里查不到 → 400(调用方传了该所不挂牌的交易对
     * / 市场类型组合)。区别于 502 交易所瞬态故障:这是确定性的配置/输入错误,不重试。典型:给 PERP
     * 交易所传了它不提供的合约 symbol。由 {@link CcxtExchangeRegistry#ccxtSymbol} 在翻译时 fail-fast 抛出。
     */
    @ExceptionHandler(SymbolNotListedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleSymbolNotListed(SymbolNotListedException e) {
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, e.getMessage(), MDC.get("traceId"));
    }

    /** 无效 query param（如未知 exchange 枚举值）→ 400，避免落入全局 catch-all 返回 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ApiResponse.error(ErrorCode.VALIDATION_FAILED, "invalid parameter: " + e.getName(), MDC.get("traceId"));
    }
}
