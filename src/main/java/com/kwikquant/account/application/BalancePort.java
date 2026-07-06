package com.kwikquant.account.application;

import com.kwikquant.account.domain.ExchangeAccount;

/**
 * 余额查询端口(live/paper 共用接口)。对齐 Executor/OrderRouter 抽象模式:
 * 上层 {@link BalanceService#fetchBalance} 不再 {@code if (PAPER)} 分支,按 account 分发到具体 adapter。
 *
 * <p>仅暴露 {@link #fetch} —— live/paper 都支持余额查询。冻结/解冻/扣减/重置是 paper 专属
 * (live 由交易所维护本地状态),不在此接口,由 {@code PaperBalanceAdapter} 单独提供。
 */
public interface BalancePort {

    BalanceSnapshot fetch(ExchangeAccount account);
}
