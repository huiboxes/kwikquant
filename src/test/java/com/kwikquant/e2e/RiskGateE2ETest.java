package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.risk.application.RiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §7.1 六链路 - 风控 E2E:RiskService 装配 + 拦截行为的完整端到端已由
 * {@code com.kwikquant.trading.interfaces.RiskNotificationE2ETest} 覆盖(涵盖 TradingService.submit →
 * RiskGate → RiskDecision 记录 → RiskTriggeredEvent → notification → WS 推)。本类作 §7.1 链路
 * 索引冒烟(bean wired),避免复制粘贴,防修改扩散。
 */
class RiskGateE2ETest extends AbstractIntegrationTest {

    @Autowired
    RiskService riskService;

    @Test
    void riskService_wiredIntoContext() {
        assertThat(riskService)
                .as("RiskService bean must be present for the trading pre-trade gate")
                .isNotNull();
    }
}
