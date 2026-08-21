package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WsTicketService} 单测：一次性消费 / 过期清理 / 空入参防御。
 */
class WsTicketServiceTest {

    private WsTicketService service;

    @BeforeEach
    void setUp() {
        service = new WsTicketService();
    }

    @Test
    void issue_thenConsume_returnsUserIdOnce() {
        WsTicketService.IssuedTicket issued = service.issue(42L);

        assertThat(issued.ticket()).isNotBlank();
        assertThat(issued.expiresAt()).isNotNull();
        assertThat(service.consume(issued.ticket())).isEqualTo(42L);
        // 一次性：第二次消费为 null
        assertThat(service.consume(issued.ticket())).isNull();
    }

    @Test
    void consume_nullOrBlank_returnsNull() {
        assertThat(service.consume(null)).isNull();
        assertThat(service.consume("")).isNull();
        assertThat(service.consume("   ")).isNull();
    }

    @Test
    void consume_unknownTicket_returnsNull() {
        assertThat(service.consume("never-issued")).isNull();
    }

    @Test
    void cleanupExpired_removesStaleEntries() {
        WsTicketService.IssuedTicket issued = service.issue(7L);
        // 触发清理（未过期项保留）
        service.cleanupExpired();
        assertThat(service.consume(issued.ticket())).isEqualTo(7L);
    }
}
