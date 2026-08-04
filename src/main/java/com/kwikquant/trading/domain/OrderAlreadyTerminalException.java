package com.kwikquant.trading.domain;

/**
 * 订单在交易所已终态(已成交/已撤销/不存在),cancel/查询操作语义已达成。
 *
 * <p>infrastructure 层({@code DefaultCcxtOrderAdapter})catch CCXT {@code OrderNotFound}(OKX 51400
 * "order has been filled, canceled or does not exist")转为此 domain exception;application 层
 * ({@code LiveExecutor})catch 它确认订单 CANCELLED——订单在交易所已不在 = 已撤销,无需再撤。
 *
 * <p>区别于 {@link OrderNotFoundException}(本地订单找不到)——本类表示<b>交易所侧</b>订单已终态。
 */
public class OrderAlreadyTerminalException extends RuntimeException {

    public OrderAlreadyTerminalException(String message) {
        super(message);
    }

    public OrderAlreadyTerminalException(String message, Throwable cause) {
        super(message, cause);
    }
}
