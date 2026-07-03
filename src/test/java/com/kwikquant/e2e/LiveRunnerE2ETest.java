package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.account.domain.ExchangeAccount;
import com.kwikquant.trading.application.LiveExecutor;
import com.kwikquant.trading.application.OrderRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §7.1 六链路 - 实盘 Runner E2E:验证 OrderRouter 按 ExchangeAccount.isPaperTrading()=false 路由到
 * LiveExecutor(CCXT 实盘)。真实 CCXT 网络调用在集成测试中不触发,仅验 wiring。
 */
class LiveRunnerE2ETest extends AbstractIntegrationTest {

    @Autowired
    OrderRouter orderRouter;

    @Test
    void orderRouter_liveAccount_routesToLiveExecutor() {
        ExchangeAccount live = new ExchangeAccount();
        live.setPaperTrading(false);
        assertThat(orderRouter.route(live)).isInstanceOf(LiveExecutor.class);
    }
}
