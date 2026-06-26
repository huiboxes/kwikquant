package com.kwikquant.account.domain;

public class AccountDisabledException extends RuntimeException {

    public AccountDisabledException() {
        super("account disabled");
    }
}
