package com.kwikquant.market.domain;

import com.kwikquant.shared.infra.ExchangeException;

public class MarketDataException extends ExchangeException {

    public MarketDataException(String message, boolean retryable) {
        super(message, retryable);
    }

    public MarketDataException(String message, Throwable cause, boolean retryable) {
        super(message, cause, retryable);
    }
}
