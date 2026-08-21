package com.kwikquant.account.domain;

public class AuthRateLimitExceededException extends RuntimeException {

    public AuthRateLimitExceededException() {
        super("too many authentication attempts");
    }
}
