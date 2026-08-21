package com.kwikquant.shared.infra;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 单节点 lease:显式化单实例部署约束。
 *
 * <p>token registry/锁/confirmToken 均为 JVM 内存态,单节点前提;误部署多实例会致两实例都
 * reconcile RUNNING strategies → 两份 worker 持两份 token 互相 revoke 抢占(资损级)。本 service 把
 * "单节点"从隐式假设变 DB 强制:第二实例启动检测到活跃 lease → 启动失败 exit 1。
 *
 * <p><b>生命周期</b>:
 * <ul>
 *   <li>{@link ApplicationStartedEvent}(context refresh 后,ReadyEvent 前)→ {@link #acquireOnStartup()}:
 *       原子 acquire(条件 UPDATE)。成功 → 启动继续;被活跃持有 → 抛 {@link ActiveLeaseHeldException}
 *       → Spring Boot 启动失败。早于 WOS reconcile(ReadyEvent)→ lease 先就位,不会无 lease 跑 reconcile。</li>
 *   <li>{@link #heartbeat()}(@Scheduled 30s):更新 last_seen_at(证明本实例活着)。</li>
 *   <li>{@link #releaseOnShutdown()}(@PreDestroy):正常停机清 lease(置空 node_id)→ 新实例无活跃 lease 直接 acquire。</li>
 * </ul>
 *
 * <p><b>崩溃恢复</b>:实例 kill -9(无 @PreDestroy)→ lease 未 release,但 heartbeat 停。新实例启动检测
 * last_seen_at 超阈值(staleMs)→ 判前一实例崩溃 → acquire(覆盖)→ 接管。stale 阈值 = heartbeat × 3(容忍抖动)。
 *
 * <p><b>node_id</b>:config/env {@code kwikquant.lease.node-id} 优先(部署侧唯一标识,如 k8s pod name),
 * 缺省 fallback 随机 UUID(每次启动变;acquire 条件 {@code node_id='' OR self OR stale} 不依赖跨重启一致)。
 * <b>不用 hostname</b>:docker-compose {@code container_name} 固定 → 跨 host 多实例 hostname 同值 → lease 失效。
 *
 * <p>此类逻辑可经 mock {@link AppLeaseMapper} 单测覆盖;真实双实例/崩溃恢复端到端留 CI。
 */
@Service
@ConditionalOnProperty(name = "kwikquant.lease.enabled", havingValue = "true", matchIfMissing = true)
public class AppLeaseService {

    private static final Logger log = LoggerFactory.getLogger(AppLeaseService.class);
    /** heartbeat 间隔(必须 < staleMs 的 1/3,保证 stale 前多次更新)。 */
    private static final long HEARTBEAT_MS = 30_000;

    private final AppLeaseMapper mapper;
    private final ConfigurableApplicationContext applicationContext;
    private final String nodeId;
    private final UUID ownerToken = UUID.randomUUID();
    private final long staleMs;
    private volatile boolean acquired;

    public AppLeaseService(
            AppLeaseMapper mapper, AppLeaseProperties props, ConfigurableApplicationContext applicationContext) {
        this.mapper = mapper;
        this.applicationContext = applicationContext;
        this.nodeId = resolveNodeId(props.nodeId());
        this.staleMs = props.staleMs();
    }

    /** node_id:config/env 优先(部署侧唯一标识);缺省 fallback 随机 UUID(实例唯一)。 */
    private static String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 启动 acquire:原子条件 UPDATE。成功(affected=1)→ 日志 + 启动继续;被活跃持有(affected=0)
     * → 抛 {@link ActiveLeaseHeldException} → Spring Boot 启动失败(ApplicationStartedEvent 在
     * ReadyEvent 前,抛异常阻止 context ready)。
     */
    @EventListener(ApplicationStartedEvent.class)
    public void acquireOnStartup() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime staleThreshold = now.minus(Duration.ofMillis(staleMs));
        int affected = mapper.acquireIfAvailable(nodeId, ownerToken, now, staleThreshold);
        if (affected == 1) {
            acquired = true;
            log.info("App lease acquired by node {} (stale threshold {}s)", nodeId, staleMs / 1000);
            return;
        }
        // affected==0: lease 被活跃持有(node_id 非自己且 last_seen 未过期)→ 拒绝启动
        AppLeaseRow holder = mapper.selectForInfo();
        String holderNode = holder != null ? holder.nodeId() : "(unknown)";
        String holderSeen = holder != null ? String.valueOf(holder.lastSeenAt()) : "(unknown)";
        throw new ActiveLeaseHeldException(holderNode, "last_seen_at=" + holderSeen + "; this node=" + nodeId);
    }

    /**
     * heartbeat:更新自己的 lease last_seen_at(证明本实例活着)。只更新自己(owner_token 匹配,防并发误更新别人的)。
     *
     * <p>affected≠1(owner_token 被覆盖)分两种:
     * <ul>
     *   <li>holder.node_id == self:同一部署单元的另一容器(测试 JVM 多 Spring context / 同 pod 重启)
     *   经 acquire 的 self 分支接管了 owner_token → 重新 acquire 续命(单 JVM 本就是一个部署单元)。</li>
     *   <li>holder.node_id ≠ self(或空):外部实例抢占 → fail-stop 关闭本实例(单节点不变量)。</li>
     * </ul>
     */
    @Scheduled(fixedDelay = HEARTBEAT_MS)
    public void heartbeat() {
        if (!acquired) {
            return;
        }
        try {
            int affected = mapper.heartbeat(ownerToken, OffsetDateTime.now(ZoneOffset.UTC));
            if (affected == 1) {
                return;
            }
            AppLeaseRow holder = mapper.selectForInfo();
            if (holder != null && nodeId.equals(holder.nodeId()) && reacquire()) {
                log.warn("App lease owner_token superseded by same-node container {}; reacquired", nodeId);
                return;
            }
            acquired = false;
            log.error("App lease ownership lost by node {} (holder={}); shutting down", nodeId, holderNodeId(holder));
            applicationContext.close();
        } catch (RuntimeException e) {
            acquired = false;
            log.error("App lease heartbeat failed for node {}; shutting down", nodeId, e);
            applicationContext.close();
        }
    }

    /** owner_token 被同 node 容器覆盖后的重新 acquire(self 分支命中);成功则心跳续命。 */
    private boolean reacquire() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return mapper.acquireIfAvailable(nodeId, ownerToken, now, now.minus(Duration.ofMillis(staleMs))) == 1;
    }

    private static String holderNodeId(AppLeaseRow holder) {
        return holder != null ? holder.nodeId() : "(unknown)";
    }

    /** 正常停机 release:清自己的 lease(置空 node_id),新实例无活跃 lease 直接 acquire。 */
    @PreDestroy
    public void releaseOnShutdown() {
        if (!acquired) {
            return;
        }
        acquired = false;
        int affected = mapper.release(ownerToken);
        log.info("App lease released by node {} (affected={})", nodeId, affected);
    }
}
