package com.kwikquant.account.application;

import com.kwikquant.shared.types.Exchange;

/**
 * 创建交易所账户的命令对象。封装 {@link ExchangeAccountService#create} 的参数，
 * 避免 7 参数（含 4 连续 String）导致传反位置且编译器无法检测。
 */
public record CreateAccountCommand(
        long userId,
        Exchange exchange,
        String label,
        String apiKey,
        String apiSecret,
        String passphrase,
        boolean paperTrading) {}
