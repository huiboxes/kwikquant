package com.kwikquant.trading.infrastructure;

/**
 * @deprecated 已迁移至 {@link com.kwikquant.trading.domain.MatchingException}。保留此类仅为向后兼容，下版本删除。
 */
@Deprecated
public class MatchingException extends com.kwikquant.trading.domain.MatchingException {
    public MatchingException(String message) {
        super(message);
    }
}
