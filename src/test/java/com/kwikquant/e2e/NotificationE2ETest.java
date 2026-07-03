package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.notification.application.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §7.1 六链路 - 通知 E2E:全流程 (事件→channel→WS 推) 由 {@code
 * com.kwikquant.trading.interfaces.RiskNotificationE2ETest} 及各 channel 单测覆盖。本类保持
 * §7.1 六链路结构完整,作 bean wiring 冒烟。
 */
class NotificationE2ETest extends AbstractIntegrationTest {

    @Autowired
    NotificationService notificationService;

    @Test
    void notificationService_wiredIntoContext() {
        assertThat(notificationService).isNotNull();
    }
}
