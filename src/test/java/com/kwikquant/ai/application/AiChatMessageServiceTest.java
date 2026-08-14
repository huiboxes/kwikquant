package com.kwikquant.ai.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.ai.domain.AiChatMessage;
import com.kwikquant.ai.infrastructure.AiChatMessageMapper;
import com.kwikquant.shared.infra.OwnershipViolationException;
import com.kwikquant.strategy.application.StrategyCrudService;
import com.kwikquant.strategy.domain.StrategyDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link AiChatMessageService} 单元测试(mock mapper)。
 *
 * <p>覆盖:saveMessage 持久化(happy path)/ loadHistory 按 created_at 升序返 List /
 * loadHistory strategyId 非 owner 抛 OwnershipViolationException / deleteAll 清 strategy 消息。
 * ownership 路径(非 owner 抛)由 StrategyCrudService.getOwned 内部 OwnershipCheck 实现,
 * 本 test mock crudService.getOwned 模拟该抛点。
 */
class AiChatMessageServiceTest {

    private AiChatMessageMapper mapper;
    private StrategyCrudService crudService;
    private AiChatMessageService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiChatMessageMapper.class);
        crudService = mock(StrategyCrudService.class);
        service = new AiChatMessageService(mapper, crudService);
    }

    @Test
    void saveMessage_shouldPersist() {
        // happy path:strategy 属当前 user,保存 user 消息(role=user, model=null)
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);

        service.saveMessage(5L, 42L, "user", "帮我优化 MA", null);

        // 验证 mapper.insert 收到的实体字段正确(user_id 取自 strategy.userId,深度防御不直接信 caller)
        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(mapper).insert(captor.capture());
        AiChatMessage saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getStrategyId()).isEqualTo(5L);
        assertThat(saved.getRole()).isEqualTo("user");
        assertThat(saved.getContent()).isEqualTo("帮我优化 MA");
        assertThat(saved.getModel()).isNull(); // user 消息 model 恒 null
    }

    @Test
    void saveMessage_aiRole_persistsModel() {
        // AI 消息(role=ai, model=溯源用):验证 model 透传持久化
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);

        service.saveMessage(5L, 42L, "assistant", "建议优化 MA 周期...", "gpt-4o");

        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(mapper).insert(captor.capture());
        AiChatMessage saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo("assistant");
        assertThat(saved.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void loadHistory_shouldReturnByStrategyCreatedAsc() {
        // happy path:返回 mapper 查询结果(已按 created_at ASC 排序,limit 200)
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        AiChatMessage m1 = new AiChatMessage();
        m1.setId(1L);
        m1.setRole("user");
        m1.setContent("first");
        m1.setCreatedAt(Instant.parse("2026-07-28T10:00:00Z"));
        AiChatMessage m2 = new AiChatMessage();
        m2.setId(2L);
        m2.setRole("assistant");
        m2.setContent("second");
        m2.setCreatedAt(Instant.parse("2026-07-28T10:00:01Z"));
        when(mapper.listByStrategy(5L, 42L, 200)).thenReturn(List.of(m1, m2));

        List<AiChatMessage> history = service.loadHistory(5L, 42L);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getContent()).isEqualTo("first");
        assertThat(history.get(1).getContent()).isEqualTo("second");
        // 验证 limit 传 200(防爆)
        verify(mapper).listByStrategy(5L, 42L, 200);
    }

    @Test
    void loadHistory_whenNotOwner_shouldThrow() {
        // ownership:crudService.getOwned 非 owner 抛 OwnershipViolationException(模拟该抛点)
        when(crudService.getOwned(5L, 42L)).thenThrow(new OwnershipViolationException("strategy"));

        assertThatThrownBy(() -> service.loadHistory(5L, 42L)).isInstanceOf(OwnershipViolationException.class);
        // 不应查 mapper(ownership 校验在前)
        verifyNoInteractions(mapper);
    }

    @Test
    void loadHistory_whenStrategyNotFound_shouldThrowAndNotQueryMapper() {
        // strategy 不存在:crudService.getOwned 抛(模拟 StrategyNotFoundException,非 OwnershipViolationException)
        when(crudService.getOwned(999L, 42L))
                .thenThrow(new com.kwikquant.strategy.domain.StrategyNotFoundException(999L));

        assertThatThrownBy(() -> service.loadHistory(999L, 42L))
                .isInstanceOf(com.kwikquant.strategy.domain.StrategyNotFoundException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void deleteAll_shouldClearByStrategy() {
        // happy path:ownership 校验通过后调 mapper.deleteByStrategy
        StrategyDefinition s = StrategyDefinition.create(42L, "MA", null, "BTC/USDT", "BINANCE", "SPOT", "1h", "{}");
        s.setId(5L);
        when(crudService.getOwned(5L, 42L)).thenReturn(s);
        when(mapper.deleteByStrategy(5L, 42L)).thenReturn(3);

        service.deleteAll(5L, 42L);

        verify(mapper).deleteByStrategy(5L, 42L);
    }

    @Test
    void deleteAll_whenNotOwner_shouldThrowAndNotCallMapper() {
        when(crudService.getOwned(5L, 42L)).thenThrow(new OwnershipViolationException("strategy"));

        assertThatThrownBy(() -> service.deleteAll(5L, 42L)).isInstanceOf(OwnershipViolationException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void saveMessage_whenNotOwner_shouldThrowAndNotCallMapper() {
        when(crudService.getOwned(5L, 42L)).thenThrow(new OwnershipViolationException("strategy"));

        assertThatThrownBy(() -> service.saveMessage(5L, 42L, "user", "hi", null))
                .isInstanceOf(OwnershipViolationException.class);
        verifyNoInteractions(mapper);
    }
}
