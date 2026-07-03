package com.kwikquant.shared.infra;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP PAT 哈希工具。pepper 通过构造器 {@code @Value("${kwikquant.mcp.pepper}")} 注入（参照
 * {@code AccountConfig} 的 {@code @Value("${kwikquant.encryption.key}")} 注入风格）。
 *
 * <p><b>查找哈希 vs salt 的设计决策（架构师决策，解决 tech-design §3.1 与 §6 的矛盾）</b>：§6 指定
 * {@code token_hash = HMAC-SHA-256(raw, pepper+salt)}（per-token 随机 salt），但 §3.1 要求 verify
 * 「先哈希再查」走 {@code uk_mcp_token_hash} 索引。per-token 随机 salt 与索引查找数学上不兼容
 * （无法在不知道 salt 的情况下预计算 salted hash 用于索引命中）。为兼顾「索引命中热路径」(§3.1)
 * 与「pepper defense-in-depth」(§6)，{@code token_hash} 使用 <b>pepper-only HMAC</b>（service 层
 * 调 {@link #hash(String, String)} 传空 salt，{@code pepper + ""} = {@code pepper}），salt 列按 §5.2
 * schema 保留并由 {@link #generateSalt()} 填充（per-token 随机），留作未来 salted-confirmation 迁移，
 * 当前不参与查找哈希。DB-only 泄漏下连哈希计算都做不了（无 pepper），满足 §6 defense-in-depth 意图。
 *
 * <ul>
 *   <li>{@link #generateToken()} → {@code kq_pat_<32hex>}（16 随机字节 hex）
 *   <li>{@link #generateSalt()} → 32 hex 字符（16 随机字节）
 *   <li>{@link #hash(String, String)} → HMAC-SHA-256(raw, pepper+salt) hex（64 字符）；salt 传入时参与 HMAC key
 * </ul>
 */
@Component
public class McpTokenHasher {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] pepperBytes;

    public McpTokenHasher(@Value("${kwikquant.mcp.pepper}") String pepper) {
        this.pepperBytes = pepper.getBytes(StandardCharsets.UTF_8);
    }

    /** 生成明文 token：{@code kq_pat_<32hex>}（16 随机字节 hex）。 */
    public String generateToken() {
        byte[] random = new byte[16];
        SECURE_RANDOM.nextBytes(random);
        return "kq_pat_" + HexFormat.of().formatHex(random);
    }

    /** 生成 per-token 随机 salt：32 hex 字符（16 随机字节）。 */
    public String generateSalt() {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /**
     * HMAC-SHA-256(raw, pepper+salt) hex（64 字符）。salt 参与 HMAC key；service 层 verify 查找路径传空 salt
     * 使 key 退化为 pepper-only（查找哈希，索引命中）。
     */
    public String hash(String raw, String salt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
            byte[] key = new byte[pepperBytes.length + saltBytes.length];
            System.arraycopy(pepperBytes, 0, key, 0, pepperBytes.length);
            System.arraycopy(saltBytes, 0, key, pepperBytes.length, saltBytes.length);
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] result = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 algorithm not available", e);
        }
    }
}
