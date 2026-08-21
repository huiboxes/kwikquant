package com.kwikquant.account.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * WebSocket 握手一次性票据（ticket）服务。
 *
 * <p>背景：浏览器对 WS upgrade 请求是否附带 SameSite=Strict cookie 存在行为差异（实测部分
 * Chromium 场景不附），纯 cookie 握手鉴权会出现永久 403。ticket 模式由 REST（access token
 * 鉴权）签发 30s 短效票据，前端拼到 {@code /ws?ticket=xxx} 握手，拦截器一次性消费。
 *
 * <p>语义：ticket 一次性消费（compute 内 get+remove 原子）+ 30s TTL + 定时清理过期项。
 * 内存存储适用于当前单节点 lease 部署；多实例部署需换共享存储（记 TD）。
 * 范式参考 {@code shared/infra/WorkerTokenService}，但语义不同（短效一次性 vs 长期有效），故独立成类。
 */
@Service
public class WsTicketService {

    /** ticket 有效期 30s：足够前端申请后立即握手，泄漏窗口最小化。 */
    private static final long TTL_MILLIS = 30_000;

    private record Entry(long userId, long expiresAtMillis) {}

    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();

    /** 为已认证用户签发一次性握手票据。 */
    public IssuedTicket issue(long userId) {
        String ticket = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + TTL_MILLIS;
        tickets.put(ticket, new Entry(userId, expiresAt));
        return new IssuedTicket(ticket, Instant.ofEpochMilli(expiresAt));
    }

    /**
     * 握手时一次性消费票据：存在且未过期则移除并返回 userId，否则 null。
     * compute 内判定+移除保证并发重连不会双消费同一 ticket。
     */
    public Long consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        final Long[] userId = new Long[1];
        tickets.compute(ticket, (key, entry) -> {
            if (entry == null || entry.expiresAtMillis() < System.currentTimeMillis()) {
                return null; // 不存在或已过期，顺带清理
            }
            userId[0] = entry.userId();
            return null; // 一次性消费：命中即移除
        });
        return userId[0];
    }

    /** 定时清理过期票据，防内存堆积（重连失败未消费的 ticket）。 */
    @Scheduled(fixedRate = 5000)
    void cleanupExpired() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAtMillis() < now);
    }

    public record IssuedTicket(String ticket, Instant expiresAt) {}
}
