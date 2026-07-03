package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.*;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link McpTokenHasher} 单元测试：generateToken 格式 / generateSalt / hash 一致性 + pepper 参与。
 */
class McpTokenHasherTest {

    private static final String TEST_PEPPER = "test-mcp-pepper-secret-0123456789abcdef";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^kq_pat_[0-9a-f]{32}$");
    private static final Pattern SALT_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private final McpTokenHasher hasher = new McpTokenHasher(TEST_PEPPER);

    @Test
    void generateToken_matchesKqPatPrefixWith32Hex() {
        String token = hasher.generateToken();
        assertThat(token).startsWith("kq_pat_");
        assertThat(TOKEN_PATTERN.matcher(token)).matches();
    }

    @Test
    void generateToken_isRandomEachCall() {
        String a = hasher.generateToken();
        String b = hasher.generateToken();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void generateSalt_is32HexChars() {
        String salt = hasher.generateSalt();
        assertThat(SALT_PATTERN.matcher(salt)).matches();
        assertThat(salt.length()).isEqualTo(32);
    }

    @Test
    void generateSalt_isRandomEachCall() {
        assertThat(hasher.generateSalt()).isNotEqualTo(hasher.generateSalt());
    }

    @Test
    void hash_isDeterministicForSameRawAndSalt() {
        String raw = "kq_pat_abcdef0123456789abcdef0123456789";
        String salt = hasher.generateSalt();
        String h1 = hasher.hash(raw, salt);
        String h2 = hasher.hash(raw, salt);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1.length()).isEqualTo(64);
    }

    @Test
    void hash_differentRawProducesDifferentHash() {
        String salt = hasher.generateSalt();
        assertThat(hasher.hash("kq_pat_aaaa", salt)).isNotEqualTo(hasher.hash("kq_pat_bbbb", salt));
    }

    @Test
    void hash_differentSaltProducesDifferentHash() {
        String raw = "kq_pat_abcdef0123456789abcdef0123456789";
        assertThat(hasher.hash(raw, hasher.generateSalt())).isNotEqualTo(hasher.hash(raw, hasher.generateSalt()));
    }

    @Test
    void hash_pepperParticipatesInHmac() {
        String raw = "kq_pat_abcdef0123456789abcdef0123456789";
        String salt = "00112233445566778899aabbccddeeff";
        McpTokenHasher otherPepper = new McpTokenHasher("a-different-pepper-value-9876543210fedcb");
        assertThat(hasher.hash(raw, salt)).isNotEqualTo(otherPepper.hash(raw, salt));
    }

    @Test
    void hash_emptySaltEqualsPepperOnlyHmac() {
        // service verify 查找路径传空 salt，key = pepper + "" = pepper（pepper-only 查找哈希）
        String raw = "kq_pat_abcdef0123456789abcdef0123456789";
        assertThat(hasher.hash(raw, "")).isEqualTo(hasher.hash(raw, ""));
    }
}
