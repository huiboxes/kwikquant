package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * InMemoryPortfolioSubscriptionRegistry 单测:register/unregister/clearSession/activeUserIds 的
 * 去重 + 引用计数语义(scheduledPush 定向推送的正确性基础)。
 */
class InMemoryPortfolioSubscriptionRegistryTest {

    private final InMemoryPortfolioSubscriptionRegistry registry = new InMemoryPortfolioSubscriptionRegistry();

    @Test
    void register_thenActiveUserIdsContainsUser() {
        registry.register("sess-1", "sub-1", 42L);
        assertThat(registry.activeUserIds()).containsExactly(42L);
    }

    @Test
    void register_sameUserTwoSessions_deduplicatedInActiveSet() {
        // 同一 userId 两个 session 订阅 → active 集合只出 1 个(去重)
        registry.register("sess-1", "sub-1", 42L);
        registry.register("sess-2", "sub-2", 42L);
        assertThat(registry.activeUserIds()).containsExactly(42L);
    }

    @Test
    void register_sameSessionTwoSubIds_bothTracked() {
        registry.register("sess-1", "sub-1", 42L);
        registry.register("sess-1", "sub-2", 43L);
        assertThat(registry.activeUserIds()).containsExactlyInAnyOrder(42L, 43L);
    }

    @Test
    void unregister_removesSubscriber() {
        registry.register("sess-1", "sub-1", 42L);
        registry.unregister("sess-1", "sub-1");
        assertThat(registry.activeUserIds()).isEmpty();
    }

    @Test
    void unregister_unknownSubId_isNoOp() {
        registry.register("sess-1", "sub-1", 42L);
        // 未注册的 subId unregister 不抛异常、不影响已有记录
        registry.unregister("sess-1", "sub-unknown");
        assertThat(registry.activeUserIds()).containsExactly(42L);
    }

    @Test
    void unregister_unknownSession_isNoOp() {
        registry.register("sess-1", "sub-1", 42L);
        registry.unregister("sess-unknown", "sub-1");
        assertThat(registry.activeUserIds()).containsExactly(42L);
    }

    @Test
    void unregister_oneOfTwoSessions_userStillActive() {
        // 引用计数语义:user 42 两 session 订阅,退一个,42 仍在集合(另一个 session 还在)
        registry.register("sess-1", "sub-1", 42L);
        registry.register("sess-2", "sub-2", 42L);
        registry.unregister("sess-1", "sub-1");
        assertThat(registry.activeUserIds()).containsExactly(42L);
    }

    @Test
    void clearSession_removesAllUserIdsOfThatSession() {
        registry.register("sess-1", "sub-1", 42L);
        registry.register("sess-1", "sub-2", 43L);
        registry.clearSession("sess-1");
        assertThat(registry.activeUserIds()).isEmpty();
    }

    @Test
    void clearSession_unknownSession_isNoOp() {
        registry.register("sess-1", "sub-1", 42L);
        registry.clearSession("sess-unknown");
        assertThat(registry.activeUserIds()).containsExactly(42L);
    }

    @Test
    void nullArguments_areIgnored() {
        registry.register(null, "sub-1", 42L);
        registry.register("sess-1", null, 42L);
        registry.unregister(null, "sub-1");
        registry.unregister("sess-1", null);
        registry.clearSession(null);
        assertThat(registry.activeUserIds()).isEmpty();
    }

    @Test
    void activeUserIds_returnsDefensiveCopySnapshot() {
        registry.register("sess-1", "sub-1", 42L);
        Set<Long> snapshot = registry.activeUserIds();
        snapshot.add(99L); // 修改快照不影响内部状态
        assertThat(registry.activeUserIds()).containsExactly(42L);
    }
}
