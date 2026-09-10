package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * /health JSON → {@link WorkerHealthSnapshot} 反序列化契约(与 {@code RealWorkerHealthProbe} 的
 * OBJECT_MAPPER 同款配置:FAIL_ON_UNKNOWN_PROPERTIES 禁用,HTTP 部分 JaCoCo 排除故在此独立锁解析)。
 *
 * <p>锁死新旧镜像混部两个方向:真实 payload 含 strategyId/consecutiveOnBarFailures 等 record 外字段
 * (必须容忍);旧镜像缺 consecutiveCallbackFailures → null(消费方 null-safe,不判 WARN 不参与判定)。
 */
class WorkerHealthSnapshotParsingTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void parsesFullNewWorkerPayload() {
        String json =
                """
                {"status":"ok","strategyId":42,"incarnation":"inc-1","lastBarAt":1700000000000,\
                "lastWsMsgAt":1700000000001,"consecutiveOrderFailures":0,\
                "consecutiveOnBarFailures":0,"consecutiveCallbackFailures":2}""";

        WorkerHealthSnapshot snap = MAPPER.readValue(json, WorkerHealthSnapshot.class);

        assertThat(snap.status()).isEqualTo("ok");
        assertThat(snap.incarnation()).isEqualTo("inc-1");
        assertThat(snap.lastBarAt()).isEqualTo(1700000000000L);
        assertThat(snap.lastWsMsgAt()).isEqualTo(1700000000001L);
        assertThat(snap.consecutiveOrderFailures()).isZero();
        assertThat(snap.consecutiveCallbackFailures()).isEqualTo(2);
    }

    @Test
    void oldWorkerPayloadWithoutCallbackFailures_parsesNull() {
        String json =
                """
                {"status":"ok","strategyId":42,"incarnation":"inc-1","lastBarAt":null,\
                "lastWsMsgAt":1700000000001,"consecutiveOrderFailures":null}""";

        WorkerHealthSnapshot snap = MAPPER.readValue(json, WorkerHealthSnapshot.class);

        assertThat(snap.consecutiveCallbackFailures()).isNull();
        assertThat(snap.lastBarAt()).isNull();
        // null-safe 消费:不判 WARN;健康判定照常(WS fresh + status ok)
        assertThat(DockerWorkerManager.shouldWarnCallbackFailures(snap, 3)).isFalse();
        assertThat(DockerWorkerManager.isWorkerHealthy(snap, 1700000000002L, 300_000L, 5))
                .isTrue();
    }
}
