package com.kwikquant.shared.infra;

public class ExchangeException extends RuntimeException {

    private final boolean retryable;

    public ExchangeException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ExchangeException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
