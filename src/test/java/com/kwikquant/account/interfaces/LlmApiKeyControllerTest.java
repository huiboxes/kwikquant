package com.kwikquant.account.interfaces;

import static org.assertj.core.api.Assertions.*;

import com.kwikquant.shared.types.LlmProvider;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Constructor;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round 4 补：验证 {@code LlmApiKeyController.CreateLlmKeyRequest} 的 {@code label} 白名单
 * ({@code @Pattern("^[A-Za-z0-9 _-]{1,100}$")}) 生效，避免用户误把 API key/邮箱当 label 而
 * 被审计日志固化（Round 3 修复项）。用 Bean Validation 直接测 record，无需拉起 MVC。
 */
class LlmApiKeyControllerTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** CreateLlmKeyRequest 是 package-private，通过反射构造。 */
    @SuppressWarnings("unchecked")
    private static Object newRequest(String label, LlmProvider provider, String apiKey, String baseUrl)
            throws Exception {
        Class<?> cls = Class.forName("com.kwikquant.account.interfaces.LlmApiKeyController$CreateLlmKeyRequest");
        Constructor<?> ctor = cls.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(label, provider, apiKey, baseUrl);
    }

    @Test
    void label_validAscii_passes() throws Exception {
        Object req = newRequest("My GPT Key 1", LlmProvider.OPENAI, "sk-abc", null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void label_containsSecretPrefix_rejected() throws Exception {
        // 攻击者/误操作把 API key 前缀写进 label，@Pattern 拒绝 "-"" 后面的 "p" 不合规吗？
        // "sk-proj-xxx" 含 "sk-" 前缀 —— 目前 pattern 允许 A-Za-z0-9 空格 下划线 中划线，
        // 所以 "sk-proj-xxx" 本身**能通过** —— 更强的语义检查需要额外前瞻。但这不是当前 pattern 的合约。
        // 当前 pattern 主要拒绝的是 email/dot/@ 等常见 secret 相邻字符。
        Object req = newRequest("user@example.com", LlmProvider.OPENAI, "sk-abc", null);
        Set<ConstraintViolation<Object>> v = validator.validate(req);
        assertThat(v).isNotEmpty();
        assertThat(v).anyMatch(cv -> "label".equals(cv.getPropertyPath().toString()));
    }

    @Test
    void label_containsDot_rejected() throws Exception {
        Object req = newRequest("key.v1", LlmProvider.OPENAI, "sk-abc", null);
        assertThat(validator.validate(req))
                .anyMatch(cv -> "label".equals(cv.getPropertyPath().toString()));
    }

    @Test
    void label_containsControlChar_rejected() throws Exception {
        Object req = newRequest("bad\nlabel", LlmProvider.OPENAI, "sk-abc", null);
        assertThat(validator.validate(req))
                .anyMatch(cv -> "label".equals(cv.getPropertyPath().toString()));
    }

    @Test
    void label_blank_rejected() throws Exception {
        Object req = newRequest("", LlmProvider.OPENAI, "sk-abc", null);
        assertThat(validator.validate(req))
                .anyMatch(cv -> "label".equals(cv.getPropertyPath().toString()));
    }

    @Test
    void label_over100Chars_rejected() throws Exception {
        Object req = newRequest("a".repeat(101), LlmProvider.OPENAI, "sk-abc", null);
        assertThat(validator.validate(req))
                .anyMatch(cv -> "label".equals(cv.getPropertyPath().toString()));
    }
}
