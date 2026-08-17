package com.kwikquant.shared.infra;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.types.McpToken;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link McpTokenMapper} 集成测试（Testcontainers，继承 {@link AbstractIntegrationTest}）。
 * V18 迁移在 Testcontainers 启动时执行成功（contextLoads 即证明）。
 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class McpTokenMapperTest extends AbstractIntegrationTest {

    @Autowired
    private McpTokenMapper mapper;

    @Autowired
    private UserMapper userMapper;

private long seedUser() {
        String u = "mcp-" + UUID.randomUUID();
        User user = new User();
        user.setUsername(u);
        user.setEmail(u + "@example.com");
        user.setPasswordHash("$argon2id$stub");
        user.setEnabled(true);
        userMapper.insert(user);
        return user.getId();
    }

    private static McpToken newToken(long userId, String name, String tokenHash) {
        Instant now = Instant.now();
        McpToken t = new McpToken();
        t.setUserId(userId);
        t.setName(name);
        t.setTokenHash(tokenHash);
        t.setSalt("00112233445566778899aabbccddeeff");
        // V47 起 scopes NOT NULL：mapper insert 显式传 #{scopes}，显式 NULL 不享受 DB DEFAULT 兜底
        t.setScopes("READ,BACKTEST,TRADE,LIVE,RISK");
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }

    @Test
    void insert_setsIdAndFindByTokenHashReturnsRow() {
        long userId = seedUser();
        // hash 确保正好 64 字符
        String tokenHash = String.format("%064d", userId);
        McpToken t = newToken(userId, "claude-desktop", tokenHash);
        mapper.insert(t);
        assertThat(t.getId()).isNotNull();

        McpToken found = mapper.findByTokenHash(tokenHash);
        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getName()).isEqualTo("claude-desktop");
        assertThat(found.getTokenHash()).isEqualTo(tokenHash);
        assertThat(found.getRevokedAt()).isNull();
        assertThat(found.getLastUsedAt()).isNull();
    }

    @Test
    void findByUserId_returnsUserTokensOrderedByCreatedAtDesc() {
        long userId = seedUser();
        McpToken t1 = newToken(userId, "first", String.format("%064d", userId) + "1".substring(0, 0) + "1");
        // 用稳定 64-char hash
        t1.setTokenHash(String.format("%063d", userId) + "1");
        t1.setCreatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        mapper.insert(t1);
        McpToken t2 = newToken(userId, "second", String.format("%063d", userId) + "2");
        t2.setCreatedAt(Instant.now());
        mapper.insert(t2);

        List<McpToken> list = mapper.findByUserId(userId);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getName()).isEqualTo("second");
        assertThat(list.get(1).getName()).isEqualTo("first");
    }

    @Test
    void updateRevokedAt_setsRevokedAtAndReturnsOneForOwner() {
        long userId = seedUser();
        String tokenHash = String.format("%063d", userId) + "r";
        McpToken t = newToken(userId, "to-revoke", tokenHash);
        mapper.insert(t);

        int updated = mapper.updateRevokedAt(t.getId(), userId);
        assertThat(updated).isEqualTo(1);
        McpToken found = mapper.findByTokenHash(tokenHash);
        assertThat(found.getRevokedAt()).isNotNull();

        // 幂等：再次吊销返回 0
        assertThat(mapper.updateRevokedAt(t.getId(), userId)).isEqualTo(0);
    }

    @Test
    void updateRevokedAt_returnsZeroForWrongUser() {
        long userId = seedUser();
        String tokenHash = String.format("%063d", userId) + "w";
        McpToken t = newToken(userId, "owned", tokenHash);
        mapper.insert(t);

        long otherUser = seedUser();
        assertThat(mapper.updateRevokedAt(t.getId(), otherUser)).isEqualTo(0);
        assertThat(mapper.findByTokenHash(tokenHash).getRevokedAt()).isNull();
    }

    @Test
    void updateLastUsedAt_setsLastUsedAt() {
        long userId = seedUser();
        String tokenHash = String.format("%063d", userId) + "l";
        McpToken t = newToken(userId, "to-touch", tokenHash);
        mapper.insert(t);

        assertThat(mapper.updateLastUsedAt(t.getId())).isEqualTo(1);
        McpToken found = mapper.findByTokenHash(tokenHash);
        assertThat(found.getLastUsedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }
}
