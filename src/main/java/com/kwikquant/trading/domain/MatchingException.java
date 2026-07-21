package com.kwikquant.trading.domain;

/** 撮合异常:订单数量/价格非法、over-fill 等。属于领域层概念,不应放在 infrastructure 包。 */
public class MatchingException extends RuntimeException {

    public MatchingException(String message) {
        super(message);
    }

    public MatchingException(String message, Throwable cause) {
        super(message, cause);
    }
}
