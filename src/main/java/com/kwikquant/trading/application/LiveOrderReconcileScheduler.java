package com.kwikquant.trading.application;

import com.kwikquant.account.application.ExchangeAccountService;
import com.kwikquant.shared.types.Exchange;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期恢复下单/撤单结果未知的 OKX 实盘订单。 */
@Component
public class LiveOrderReconcileScheduler {

    private final ExchangeAccountService accountService;
    private final LiveExecutor liveExecutor;

    public LiveOrderReconcileScheduler(ExchangeAccountService accountService, LiveExecutor liveExecutor) {
        this.accountService = accountService;
        this.liveExecutor = liveExecutor;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void reconcile() {
        accountService.findAll().stream()
                .filter(account -> !account.isPaperTrading())
                .filter(account -> account.getExchange() == Exchange.OKX)
                .forEach(liveExecutor::reconcileAccount);
    }
}
