package com.kwikquant.mcp.application;

import com.kwikquant.shared.infra.McpConfirmTokenInvalidException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 高危写操作两阶段确认令牌(替代旧裸 boolean confirm——boolean 可被 agent 单回合自带,不构成防线)。
 *
 * <p><b>协议</b>:第一阶段调用(不带 confirmToken)不执行副作用,返回 preview + 新令牌;
 * 第二阶段携令牌复述**完全相同**的参数,校验通过后一次性消费并执行。
 *
 * <p><b>防御面</b>:
 * <ul>
 *   <li>指纹绑定 (userId, toolName, 规范化参数):防"预览 A 确认 B"参数替换;
 *   <li>一次性消费:防重放(同一 token 第二次调用即失效);
 *   <li>短 TTL(默认 120s):限制令牌窗口;
 *   <li>跨用户不可用:指纹含 userId,他人令牌校验不过。
 * </ul>
 *
 * <p><b>内存态与单节点假设一致</b>(同 WorkerTokenService):重启丢失仅影响待确认请求(用户重新发起
 * 第一阶段即可),无正确性风险;多实例部署需外置(见部署红线)。
 */
@Component
public class McpConfirmTokenService {

    /** 令牌表软上限:超限强制全量清理(正常流量远达不到,纯防异常堆积)。 */
    static final int MAX_PENDING = 10_000;

    private final ConcurrentHashMap<String, ConfirmEntry> tokens = new ConcurrentHashMap<>();
    private final long ttlSec;

    public McpConfirmTokenService(@Value("${kwikquant.mcp.confirm-ttl-sec:120}") long ttlSec) {
        this.ttlSec = ttlSec;
    }

    public record ConfirmTokenIssue(String token, long expiresInSec) {}

    record ConfirmEntry(long userId, String toolName, String fingerprint, Instant expiresAt) {}

    /** 签发确认令牌(第一阶段)。返回 token + TTL;不产生任何业务副作用。 */
    public ConfirmTokenIssue issue(long userId, String toolName, String canonicalParams) {
        purgeExpired();
        String token = UUID.randomUUID().toString();
        tokens.put(
                token,
                new ConfirmEntry(
                        userId,
                        toolName,
                        fingerprint(userId, toolName, canonicalParams),
                        Instant.now().plusSeconds(ttlSec)));
        return new ConfirmTokenIssue(token, ttlSec);
    }

    /**
     * 校验并一次性消费令牌(第二阶段)。通过即删除(不可重放);任何校验失败抛
     * {@link McpConfirmTokenInvalidException}(10006)且不泄露其他用户令牌存在性。
     */
    public void consume(long userId, String toolName, String canonicalParams, String token) {
        if (token == null || token.isBlank()) {
            // 调用方应先走 preview 分支;直接空 token 进 consume 属协议误用
            throw new McpConfirmTokenInvalidException("confirmToken required for this operation");
        }
        ConfirmEntry entry = tokens.remove(token); // 一次性:先摘除,失败路径也不留可重放令牌
        if (entry == null) {
            throw new McpConfirmTokenInvalidException("confirmToken invalid or already used");
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            throw new McpConfirmTokenInvalidException(
                    "confirmToken expired, re-invoke without token to get a new preview");
        }
        if (entry.userId() != userId) {
            throw new McpConfirmTokenInvalidException("confirmToken invalid or already used");
        }
        if (!entry.toolName().equals(toolName)) {
            throw new McpConfirmTokenInvalidException("confirmToken was issued for a different operation");
        }
        String expected = fingerprint(userId, toolName, canonicalParams);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), entry.fingerprint().getBytes(StandardCharsets.UTF_8))) {
            throw new McpConfirmTokenInvalidException(
                    "confirmToken does not match request parameters, re-invoke without token to get a new preview");
        }
    }

    long pendingCount() {
        return tokens.size();
    }

    private void purgeExpired() {
        if (tokens.size() > MAX_PENDING) {
            tokens.clear(); // 异常堆积兜底:全部作废,用户重新走第一阶段(无正确性影响)
            return;
        }
        Instant now = Instant.now();
        Iterator<Map.Entry<String, ConfirmEntry>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }
    }

    private static String fingerprint(long userId, String toolName, String canonicalParams) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest =
                    md.digest((userId + "\n" + toolName + "\n" + canonicalParams).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
