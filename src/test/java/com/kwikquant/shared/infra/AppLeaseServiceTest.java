package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * AppLeaseService 单测:mock {@link AppLeaseMapper},验证启动 acquire(成功/被活跃拒 2 分支)+ heartbeat +
 * release。stale 阈值计算(now - staleMs)用 ArgumentCaptor 验同一 now 基准。真实双实例/崩溃恢复留 CI。
 */
class AppLeaseServiceTest {

    private final AppLeaseMapper mapper = mock(AppLeaseMapper.class);
    private final ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    // nodeId="node-A"(非空,不触发 UUID fallback,测试可复现);staleMs=90s
    private final AppLeaseService service =
            new AppLeaseService(mapper, new AppLeaseProperties("node-A", 90_000), applicationContext);

    @Test
    void acquireOnStartup_acquires_whenAvailable() {
        // acquireIfAvailable 返 1(无 lease / self 重连 / stale 过期 三条件均走成功路径)→ 不抛
        when(mapper.acquireIfAvailable(eq("node-A"), any(), any(), any())).thenReturn(1);

        service.acquireOnStartup();

        // 验 staleThreshold = now - 90s(同一 now 基准,精确 85-95s 无 flaky)
        ArgumentCaptor<OffsetDateTime> nowCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> staleCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(mapper).acquireIfAvailable(eq("node-A"), any(), nowCaptor.capture(), staleCaptor.capture());
        OffsetDateTime nowParam = nowCaptor.getValue();
        OffsetDateTime staleParam = staleCaptor.getValue();
        assertThat(staleParam.isBefore(nowParam)).isTrue(); // stale 是过去的
        assertThat(Duration.between(staleParam, nowParam).toSeconds()).isBetween(85L, 95L); // = staleMs
    }

    @Test
    void acquireOnStartup_throws_whenActiveLeaseHeld() {
        // acquireIfAvailable 返 0(被活跃持有)→ 抛 ActiveLeaseHeldException,含 holder node_id + this node
        when(mapper.acquireIfAvailable(eq("node-A"), any(), any(), any())).thenReturn(0);
        when(mapper.selectForInfo())
                .thenReturn(new AppLeaseRow(
                        "node-B",
                        OffsetDateTime.now().minusSeconds(10),
                        OffsetDateTime.now().minusSeconds(5)));

        assertThatThrownBy(() -> service.acquireOnStartup())
                .isInstanceOf(ActiveLeaseHeldException.class)
                .hasMessageContaining("node-B") // holder
                .hasMessageContaining("node-A") // this node
                .satisfies(e -> assertThat(((ActiveLeaseHeldException) e).holderNodeId())
                        .isEqualTo("node-B"));
    }

    @Test
    void acquireOnStartup_throws_withUnknownHolder_whenSelectReturnsNull() {
        // holder 行查不到(selectForInfo null)→ message 用 (unknown),仍抛(单节点约束不放宽)
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(0);
        when(mapper.selectForInfo()).thenReturn(null);

        assertThatThrownBy(() -> service.acquireOnStartup())
                .isInstanceOf(ActiveLeaseHeldException.class)
                .hasMessageContaining("(unknown)");
    }

    @Test
    void heartbeat_updatesOwnLease() {
        // heartbeat 只更新自己的(owner_token 匹配,防并发误更新别人的)
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1);
        when(mapper.heartbeat(any(), any())).thenReturn(1);
        service.acquireOnStartup();

        service.heartbeat();

        verify(mapper).heartbeat(any(), any());
    }

    @Test
    void heartbeat_sameNodeContainerSuperseded_reacquiresAndKeepsRunning() {
        // owner_token 被同 node_id 容器(测试 JVM 多 context / 同 pod 重启)经 self 分支覆盖 →
        // 重新 acquire 续命,不关闭(单 JVM 本就是一个部署单元)
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1);
        when(mapper.heartbeat(any(), any())).thenReturn(0);
        when(mapper.selectForInfo())
                .thenReturn(new AppLeaseRow(
                        "node-A",
                        OffsetDateTime.now().minusSeconds(10),
                        OffsetDateTime.now().minusSeconds(5)));
        service.acquireOnStartup();

        service.heartbeat();

        verify(mapper, times(2)).acquireIfAvailable(eq("node-A"), any(), any(), any()); // 启动 + 续命
        verify(applicationContext, times(0)).close();
    }

    @Test
    void heartbeat_foreignNodeHolder_closesApplicationContext() {
        // owner_token 丢失且持有者 node_id ≠ self → 外部实例抢占,fail-stop 关闭
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1);
        when(mapper.heartbeat(any(), any())).thenReturn(0);
        when(mapper.selectForInfo())
                .thenReturn(new AppLeaseRow(
                        "node-B",
                        OffsetDateTime.now().minusSeconds(10),
                        OffsetDateTime.now().minusSeconds(5)));
        service.acquireOnStartup();

        service.heartbeat();

        verify(applicationContext).close();
    }

    @Test
    void heartbeat_sameNodeButReacquireFails_closesApplicationContext() {
        // 同 node 但 reacquire 也失败(极端竞态)→ 保守关闭(单节点不变量不放宽)
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1, 0);
        when(mapper.heartbeat(any(), any())).thenReturn(0);
        when(mapper.selectForInfo())
                .thenReturn(new AppLeaseRow(
                        "node-A",
                        OffsetDateTime.now().minusSeconds(10),
                        OffsetDateTime.now().minusSeconds(5)));
        service.acquireOnStartup();

        service.heartbeat();

        verify(applicationContext).close();
    }

    @Test
    void releaseOnShutdown_clearsOwnLease() {
        // release 只清自己的(node_id=self → 置空),让新实例无活跃 lease 直接 acquire
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1);
        when(mapper.release(any())).thenReturn(1);
        service.acquireOnStartup();

        service.releaseOnShutdown();

        verify(mapper).release(any());
    }

    @Test
    void resolveNodeId_fallsBackToUuidWhenConfigBlank() {
        // config 空 → fallback 随机 UUID(实例唯一,非 hostname);两次 new 不同 UUID
        AppLeaseService s1 = new AppLeaseService(mapper, new AppLeaseProperties(null, 90_000), applicationContext);
        AppLeaseService s2 = new AppLeaseService(mapper, new AppLeaseProperties("  ", 90_000), applicationContext);

        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1);
        s1.acquireOnStartup();
        s2.acquireOnStartup();

        // 两次 acquire 捕获的 nodeId(构造时生成的 UUID)不同(随机 UUID 不重复)
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).acquireIfAvailable(idCaptor.capture(), any(), any(), any());
        List<String> ids = idCaptor.getAllValues();
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isNotBlank();
        assertThat(ids.get(1)).isNotBlank();
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
    }

    @Test
    void heartbeat_lostOwnership_closesApplicationContext() {
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1);
        when(mapper.heartbeat(any(), any())).thenReturn(0);
        service.acquireOnStartup();

        service.heartbeat();

        verify(applicationContext).close();
    }

    @Test
    void heartbeat_databaseFailure_closesApplicationContext() {
        when(mapper.acquireIfAvailable(any(), any(), any(), any())).thenReturn(1);
        when(mapper.heartbeat(any(), any())).thenThrow(new IllegalStateException("database unavailable"));
        service.acquireOnStartup();

        service.heartbeat();

        verify(applicationContext).close();
    }
}
