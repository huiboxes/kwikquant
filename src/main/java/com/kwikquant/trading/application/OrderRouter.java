package com.kwikquant.trading.application;

import com.kwikquant.account.domain.ExchangeAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Order 路由器。按 ExchangeAccount.isPaperTrading() 选 Executor。
 *
 * <p>使用 ApplicationContext 延迟解析 PaperExecutor / LiveExecutor，避免循环依赖（OrderRouter → Executors →
 * ExecutionService → ...）。
 */
@Component
public class OrderRouter {

    private final ApplicationContext applicationContext;

    @Autowired
    public OrderRouter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public Executor route(ExchangeAccount account) {
        if (account.isPaperTrading()) {
            return applicationContext.getBean(PaperExecutor.class);
        }
        return applicationContext.getBean(LiveExecutor.class);
    }
}
