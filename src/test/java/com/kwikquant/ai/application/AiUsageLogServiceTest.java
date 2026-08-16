package com.kwikquant.ai.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kwikquant.ai.domain.AiUsageSource;
import com.kwikquant.ai.infrastructure.AiUsageLogMapper;
import org.junit.jupiter.api.Test;

class AiUsageLogServiceTest {

    private final AiUsageLogMapper mapper = mock(AiUsageLogMapper.class);
    private final AiUsageLogService service = new AiUsageLogService(mapper);

    @Test
    void log_withUsage_insertsAndSetsSourceLowercase() {
        service.log(1L, 2L, "gpt-4o", 100, 50, AiUsageSource.CHAT);

        verify(mapper).insert(any());
    }

    @Test
    void log_whenZeroUsage_skipsInsert() {
        service.log(1L, 2L, "gpt-4o", 0, 0, AiUsageSource.CHAT);

        verifyNoInteractions(mapper);
    }

    @Test
    void log_whenOnlyPromptToken_positive_inserts() {
        service.log(1L, 2L, "gpt-4o", 10, 0, AiUsageSource.TEST);

        verify(mapper).insert(any());
    }

    @Test
    void log_whenOnlyCompletionToken_positive_inserts() {
        service.log(1L, 2L, "claude-sonnet", 0, 20, AiUsageSource.SUMMARY);

        verify(mapper).insert(any());
    }

    @Test
    void log_whenMapperThrowsException_suppressesError() {
        doThrow(new RuntimeException("DB down")).when(mapper).insert(any());

        assertThatCode(() -> service.log(1L, 2L, "gpt-4o", 100, 50, AiUsageSource.CHAT))
                .doesNotThrowAnyException();
    }
}