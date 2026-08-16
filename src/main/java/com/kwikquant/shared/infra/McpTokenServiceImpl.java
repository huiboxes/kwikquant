package com.kwikquant.shared.infra;

import com.kwikquant.shared.types.McpToken;
import com.kwikquant.shared.types.McpTokenIssueResult;
import com.kwikquant.shared.types.McpTokenPrincipal;
import com.kwikquant.shared.types.McpTokenScope;
import com.kwikquant.shared.types.McpTokenView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link McpTokenService} 默认实现。
 *
 * <p><b>verify 事务边界</b>：verify 本身非事务（read + touch）；{@link #touchLastUsedAt(long)} 走
 * {@code @Transactional(REQUIRES_NEW)} 独立事务，通过 self-proxy 调用（@Lazy 自注入避免循环依赖），
 * 包独立 try-catch swallow + log.warn —— 鉴权决策与 last_used_at 写入解耦，update 失败不阻断放行。
 * 单元测试以 {@code new} 直接构造（无 Spring 代理），{@code self} 为 null，verify 回退到 {@code this} 直调，
 * {@code @Transactional} 在单元测试不生效（mock mapper 直抛，swallow 行为可测）。
 */
@Service
public class McpTokenServiceImpl implements McpTokenService {

    private static final Logger log = LoggerFactory.getLogger(McpTokenServiceImpl.class);

    private final McpTokenMapper mapper;
    private final McpTokenHasher hasher;
    private McpTokenServiceImpl self;

    public McpTokenServiceImpl(McpTokenMapper mapper, McpTokenHasher hasher) {
        this.mapper = mapper;
        this.hasher = hasher;
    }

    /** Spring @Lazy 自注入，让 verify 通过 proxy 调 {@link #touchLastUsedAt} 触发 REQUIRES_NEW。 */
    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@Lazy McpTokenServiceImpl self) {
        this.self = self;
    }

    /** 默认/强制 TTL 边界：未指定 90 天，上限 365 天（杜绝永久全权凭证）。 */
    static final int DEFAULT_TTL_DAYS = 90;

    static final int MAX_TTL_DAYS = 365;

    @Override
    @Transactional
    public McpTokenIssueResult issue(long userId, String name, Set<McpTokenScope> scopes, Integer expiresInDays) {
        Set<McpTokenScope> effectiveScopes =
                (scopes == null || scopes.isEmpty()) ? McpTokenScope.DEFAULT : Set.copyOf(scopes);
        int ttlDays = expiresInDays == null ? DEFAULT_TTL_DAYS : Math.min(Math.max(expiresInDays, 1), MAX_TTL_DAYS);
        String rawToken = hasher.generateToken();
        String salt = hasher.generateSalt();
        // 查找哈希使用 pepper-only（空 salt，见 McpTokenHasher 决策说明）；salt 列按 schema 保留填充。
        String tokenHash = hasher.hash(rawToken, "");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttlDays, ChronoUnit.DAYS);
        McpToken entity = new McpToken();
        entity.setUserId(userId);
        entity.setName(name);
        entity.setTokenHash(tokenHash);
        entity.setSalt(salt);
        entity.setScopes(McpTokenScope.toCsv(effectiveScopes));
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            mapper.insert(entity);
        } catch (DataIntegrityViolationException e) {
            // uk_mcp_user_name(user_id, name) 冲突
            throw new DuplicateMcpTokenException(name);
        }
        return new McpTokenIssueResult(
                entity.getId(),
                rawToken,
                name,
                effectiveScopes.stream().sorted().toList(),
                now,
                expiresAt);
    }

    @Override
    @Transactional
    public void revoke(long tokenId, long userId) {
        int updated = mapper.updateRevokedAt(tokenId, userId);
        if (updated == 0) {
            // 不存在 / 已吊销 / 越权 — 统一 NOT_FOUND，不泄露「已吊销」与「不存在」的区别
            throw new ResourceNotFoundException("mcp_token", tokenId);
        }
    }

    @Override
    public List<McpTokenView> listByUser(long userId) {
        return mapper.findByUserId(userId).stream().map(this::toView).toList();
    }

    @Override
    public McpTokenPrincipal verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String tokenHash = hasher.hash(rawToken, "");
        McpToken token = mapper.findByTokenHash(tokenHash);
        if (token == null) {
            return null;
        }
        if (token.getRevokedAt() != null) {
            return null;
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            return null;
        }
        try {
            // 通过 self-proxy 调用，触发 REQUIRES_NEW；单元测试 self=null 回退到 this 直调。
            McpTokenServiceImpl proxy = self != null ? self : this;
            proxy.touchLastUsedAt(token.getId());
        } catch (DataAccessException e) {
            // swallow + log.warn：last_used_at 写入失败不阻断鉴权放行（Fail-open on touch）
            log.warn("mcp token last_used_at update failed for tokenId={}", token.getId(), e);
        }
        return new McpTokenPrincipal(token.getUserId(), McpTokenScope.parseCsv(token.getScopes()));
    }

    /** REQUIRES_NEW 独立事务：仅写 last_used_at/updated_at。public 供 self-proxy 调用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchLastUsedAt(long tokenId) {
        mapper.updateLastUsedAt(tokenId);
    }

    private McpTokenView toView(McpToken t) {
        return new McpTokenView(
                t.getId(),
                t.getName(),
                McpTokenScope.parseCsv(t.getScopes()).stream().sorted().toList(),
                t.getCreatedAt(),
                t.getLastUsedAt(),
                t.getExpiresAt(),
                t.getRevokedAt());
    }
}
