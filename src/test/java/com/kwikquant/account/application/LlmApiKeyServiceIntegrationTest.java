package com.kwikquant.account.application;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.AbstractIntegrationTest;
import com.kwikquant.KwikquantApplication;
import com.kwikquant.account.domain.LlmApiKey;
import com.kwikquant.account.domain.User;
import com.kwikquant.account.infrastructure.RefreshTokenMapper;
import com.kwikquant.account.infrastructure.RefreshTokenMapper.RefreshTokenRow;
import com.kwikquant.account.infrastructure.UserMapper;
import com.kwikquant.shared.types.LlmProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** 集成测试：LlmApiKeyService 真实加密往返（real KMS + DB）+ LlmApiKey 全字段。 */
@SpringBootTest(classes = KwikquantApplication.class)
@TestPropertySource(
        properties = {
            "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        })
class LlmApiKeyServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    LlmApiKeyService keyService;

    @Autowired
    RefreshTokenMapper refreshTokenMapper;

    @Autowired
    UserMapper userMapper;

    private static long uniqueUserId() {
        return System.nanoTime() % 1_000_000L + 1_000_000L;
    }

    /**
     * 预置一个真实 user，返回其 id。因 refresh_tokens.user_id 有外键约束（V2_1__create_refresh_tokens.sql），
     * seedActiveToken 前必须有对应 users 行。
     */
    private long seedUser() {
        String u = "test-" + UUID.randomUUID();
        User user = new User();
        user.setUsername(u);
        user.setEmail(u + "@example.com");
        user.setPasswordHash("$argon2id$stub"); // pw hash 结构无关，只满足 NOT NULL
        user.setEnabled(true);
        userMapper.insert(user);
        return user.getId();
    }

    /** 预置一个未过期未撤销的 refresh token，用于验证 create/delete 触发 revokeAllByUserId 真实生效。 */
    private String seedActiveToken(long userId) {
        String jti = UUID.randomUUID().toString();
        refreshTokenMapper.insert(new RefreshTokenRow(jti, userId, Instant.now().plusSeconds(3600)));
        return jti;
    }

    @Test
    void create_andDecrypt_roundTripsThroughRealEncryption() {
        // V50 起 llm_api_keys.user_id 有 FK → users：创建密钥前必须有真实 user 行
        long userId = seedUser();
        String fullKey = "sk-proj-abcdef123456";
        LlmApiKey saved = keyService.create(userId, "My GPT", LlmProvider.OPENAI, fullKey, null, null);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getApiKey()).isEqualTo("3456"); // 末尾4位明文
        assertThat(saved.getProvider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(saved.getKeyVersion()).isPositive();

        // createdAt/updatedAt 由 DB DEFAULT now() 填充，service.create 返回的实体未回填，re-fetch 验证
        LlmApiKey reloaded = keyService.getOwned(saved.getId(), userId);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();

        // 解密还原完整 key（real KMS + AES-GCM）
        assertThat(keyService.decryptSecret(reloaded)).isEqualTo(fullKey);
    }

    @Test
    void listByUser_returnsMaskedViews() {
        long userId = seedUser();
        keyService.create(userId, "label1", LlmProvider.OPENAI, "sk-proj-aaa111", null, null);

        List<LlmApiKeyService.LlmApiKeyView> views = keyService.listByUser(userId);
        assertThat(views).hasSize(1);
        LlmApiKeyService.LlmApiKeyView v = views.get(0);
        assertThat(v.label()).isEqualTo("label1");
        assertThat(v.provider()).isEqualTo(LlmProvider.OPENAI);
        // key "sk-proj-aaa111" 末尾4位 = "a111"
        assertThat(v.apiKeyMasked()).isEqualTo("sk-proj...a111");
        assertThat(v.createdAt()).isNotNull();
    }

    @Test
    void getOwned_enforcesOwnership() {
        long owner = seedUser();
        long other = seedUser();
        LlmApiKey key = keyService.create(owner, "k", LlmProvider.OPENAI, "sk-proj-xyz999", null, null);

        assertThat(keyService.getOwned(key.getId(), owner).getId()).isEqualTo(key.getId());
        assertThatThrownBy(() -> keyService.getOwned(key.getId(), other))
                .isInstanceOf(com.kwikquant.shared.infra.OwnershipViolationException.class);
    }

    @Test
    void delete_removesKey_andRevokesActiveRefreshTokens() {
        long userId = seedUser();
        LlmApiKey key = keyService.create(userId, "k", LlmProvider.OPENAI, "sk-proj-del123", null, null);
        // create 已 revoke，seed 新 token 用于纯粹验证 delete
        String jti = seedActiveToken(userId);
        assertThat(refreshTokenMapper.findActiveByUserId(userId))
                .extracting(RefreshTokenRow::jti)
                .contains(jti);

        keyService.delete(key.getId(), userId);

        assertThatThrownBy(() -> keyService.getOwned(key.getId(), userId))
                .isInstanceOf(com.kwikquant.shared.infra.ResourceNotFoundException.class);
        // LLM key 删除必须撤销活跃 RefreshToken —— 走真实 SQL 而非 mock
        assertThat(refreshTokenMapper.findActiveByUserId(userId)).isEmpty();
        assertThat(refreshTokenMapper.findByJti(jti).isRevoked()).isTrue();
    }

    @Test
    void create_revokesActiveRefreshTokens() {
        long userId = seedUser();
        String jti = seedActiveToken(userId);
        assertThat(refreshTokenMapper.findActiveByUserId(userId))
                .extracting(RefreshTokenRow::jti)
                .contains(jti);

        keyService.create(userId, "k", LlmProvider.OPENAI, "sk-proj-crt123", null, null);

        assertThat(refreshTokenMapper.findActiveByUserId(userId)).isEmpty();
        assertThat(refreshTokenMapper.findByJti(jti).isRevoked()).isTrue();
    }

    @Test
    void create_openAiCompatibleRequiresBaseUrl() {
        long userId = uniqueUserId();
        assertThatThrownBy(() -> keyService.create(
                        userId, "k", LlmProvider.OPENAI_COMPATIBLE, "sk-x123", null, List.of("deepseek-chat")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_openAiCompatibleWithModel_roundTrips() {
        long userId = seedUser();
        LlmApiKey saved = keyService.create(
                userId,
                "k",
                LlmProvider.OPENAI_COMPATIBLE,
                "sk-x123456",
                "https://api.deepseek.com/v1",
                List.of("deepseek-chat"));

        // SQL 列映射 + view 透传一次锁死:re-fetch 验 model 真持久化(防 mapper SELECT 漏 model 列致 unit test 假绿)
        LlmApiKey reloaded = keyService.getOwned(saved.getId(), userId);
        assertThat(keyService.defaultModelOf(reloaded)).isEqualTo("deepseek-chat");

        // listByUser 的 view.model() 透传
        List<LlmApiKeyService.LlmApiKeyView> views = keyService.listByUser(userId);
        assertThat(views.get(0).availableModels()).containsExactly("deepseek-chat");
    }

    @Test
    void create_openAiCompatibleRequiresModel() {
        long userId = uniqueUserId();
        // baseUrl 合法但 model=null → 校验拦截(对称 baseUrl 必填,与 line 145 同款)
        assertThatThrownBy(() -> keyService.create(
                        userId, "k", LlmProvider.OPENAI_COMPATIBLE, "sk-x123", "https://api.deepseek.com/v1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }
}
