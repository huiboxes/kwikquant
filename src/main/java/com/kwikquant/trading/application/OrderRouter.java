package com.kwikquant.trading.application;

import com.kwikquant.account.domain.ExchangeAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Order 路由器。按 ExchangeAccount.isPaperTrading() 选 Executor。
 *
 * <p>构造注入 PaperExecutor / LiveExecutor(两者依赖 ExecutionService,
 * ExecutionService 不依赖 Executor,无循环依赖。原 ApplicationContext.getBean
 * 延迟解析是过时 hack,实际无循环)。
 */
@Component
public class OrderRouter {

    private final PaperExecutor paperExecutor;
    private final LiveExecutor liveExecutor;

    @Autowired
    public OrderRouter(PaperExecutor paperExecutor, LiveExecutor liveExecutor) {
        this.paperExecutor = paperExecutor;
        this.liveExecutor = liveExecutor;
    }

    public Executor route(ExchangeAccount account) {
        return account.isPaperTrading() ? paperExecutor : liveExecutor;
    }
}
