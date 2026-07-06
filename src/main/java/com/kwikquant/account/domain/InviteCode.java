package com.kwikquant.account.domain;

import java.time.Instant;

/**
 * 邀请码实体(注册门禁,V20 migration)。
 *
 * <p>消费语义由 {@code maxUses/usedCount} 表达:{@code usedCount < maxUses} 即可用。注册成功后
 * {@code usedCount++}(走 {@code InviteCodeMapper.incrementUsedCount} 乐观锁,WHERE used_count < max_uses,
 * 并发安全不超卖)。{@code enabled=false} 或 {@code expiresAt < now()} 视为失效。
 *
 * <p>不记录"哪个用户用了哪个码"(简化);如需追踪后续加 invite_redemptions 表。
 */
public record InviteCode(
        String code, int maxUses, int usedCount, Instant expiresAt, boolean enabled, Instant createdAt) {}
