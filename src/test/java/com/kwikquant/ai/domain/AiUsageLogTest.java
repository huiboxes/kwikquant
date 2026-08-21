package com.kwikquant.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** AiUsageLog 实体全字段 getter/setter 往返（此前 0% 覆盖）。 */
class AiUsageLogTest {

    @Test
    void settersAndGetters_roundTripAllFields() {
        Instant createdAt = Instant.parse("2026-08-16T09:00:00Z");
        AiUsageLog entity = new AiUsageLog();
        entity.setId(1L);
        entity.setUserId(42L);
        entity.setKeyId(7L);
        entity.setModel("claude-opus-4");
        entity.setPromptTokens(100);
        entity.setCompletionTokens(20);
        entity.setSource("chat");
        entity.setCreatedAt(createdAt);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getUserId()).isEqualTo(42L);
        assertThat(entity.getKeyId()).isEqualTo(7L);
        assertThat(entity.getModel()).isEqualTo("claude-opus-4");
        assertThat(entity.getPromptTokens()).isEqualTo(100);
        assertThat(entity.getCompletionTokens()).isEqualTo(20);
        assertThat(entity.getSource()).isEqualTo("chat");
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
    }
}
