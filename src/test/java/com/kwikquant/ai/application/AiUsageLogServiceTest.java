package com.kwikquant.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kwikquant.ai.domain.AiUsageLog;
import com.kwikquant.ai.domain.AiUsageSource;
import com.kwikquant.ai.infrastructure.AiUsageLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link AiUsageLogService} 单测：落库字段、无 usage 早退、DB 异常兜底。 */
class AiUsageLogServiceTest {

    private final AiUsageLogMapper mapper = mock(AiUsageLogMapper.class);
    private final AiUsageLogService service = new AiUsageLogService(mapper);

    @Test
    void log_persistsAllFields_withLowercaseSource() {
        service.log(42L, 7L, "claude-opus-4", 100, 20, AiUsageSource.CHAT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AiUsageLog> captor = ArgumentCaptor.forClass(AiUsageLog.class);
        verify(mapper).insert(captor.capture());
        AiUsageLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getKeyId()).isEqualTo(7L);
        assertThat(saved.getModel()).isEqualTo("claude-opus-4");
        assertThat(saved.getPromptTokens()).isEqualTo(100);
        assertThat(saved.getCompletionTokens()).isEqualTo(20);
        assertThat(saved.getSource()).isEqualTo("chat"); // AiUsageSource.name() 小写
    }

    @Test
    void log_zeroUsage_notPersisted() {
        service.log(42L, 7L, "claude-opus-4", 0, 0, AiUsageSource.SUMMARY);

        verify(mapper, never()).insert(any());
    }

    @Test
    void log_mapperFailure_swallowedNotPropagated() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any());

        // usage 是计费副产物：DB 异常仅 warn，不得传播到 SSE 主流程
        assertThatCode(() -> service.log(42L, 7L, "claude-opus-4", 5, 0, AiUsageSource.TEST))
                .doesNotThrowAnyException();
    }
}
