package com.kwikquant.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.strategy.application.AiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §7.1 六链路 - AI 辅助 E2E:验证 AiChatService bean 已装配并可提供 SSE Flux;实际 LLM 调用需要
 * API key 且走外部服务,单元测试(:{@code AiChatServiceTest}) 中 mock LLM adapter 覆盖具体行为。
 * 本 E2E 只作冒烟级 wiring 检查。
 */
class AiChatE2ETest extends AbstractIntegrationTest {

    @Autowired
    AiChatService aiChatService;

    @Test
    void aiChatService_wiredIntoContext() {
        assertThat(aiChatService).isNotNull();
        // chat 方法签名保证与前端契约一致(Flux<ServerSentEvent<String>>)
        var chatMethod = java.util.Arrays.stream(AiChatService.class.getDeclaredMethods())
                .filter(m -> "chat".equals(m.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(chatMethod.getReturnType().getName()).isEqualTo("reactor.core.publisher.Flux");
    }
}
