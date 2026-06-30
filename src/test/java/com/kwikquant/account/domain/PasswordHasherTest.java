package com.kwikquant.account.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashAndVerify() {
        String hash = PasswordHasher.hash("myPassword123");
        assertTrue(hash.startsWith("$argon2id$"));
        assertTrue(PasswordHasher.verify("myPassword123", hash));
    }

    @Test
    void wrongPasswordFails() {
        String hash = PasswordHasher.hash("correct");
        assertFalse(PasswordHasher.verify("wrong", hash));
    }

    @Test
    void differentHashesForSamePassword() {
        String h1 = PasswordHasher.hash("same");
        String h2 = PasswordHasher.hash("same");
        assertNotEquals(h1, h2);
        assertTrue(PasswordHasher.verify("same", h1));
        assertTrue(PasswordHasher.verify("same", h2));
    }

    @Test
    void invalidFormatThrows() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.verify("pw", "not-a-hash"));
    }

    /**
     * 守护测试:verify 必须使用哈希串里存储的 m/t/p 参数,而不是类常量。
     * 否则一旦调参,所有用旧参数哈希的用户密码将全部无法验证(且阻断唯一的参数迁移路径)。
     */
    @Test
    void verifyUsesParamsFromStoredHashNotClassConstants() {
        String raw = "param-portable-pw";
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        // 故意用 t=2,与类常量 ITERATIONS=3 不同
        int storedIterations = 2;
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(storedIterations)
                .withMemoryAsKB(65536)
                .withParallelism(1)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] hash = new byte[32];
        gen.generateBytes(raw.toCharArray(), hash);

        String encoded = "$argon2id$v=19$m=65536,t=2,p=1$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(hash);

        // 修复前(bug):verify 用类常量 t=3 算 testHash → 与 stored hash(t=2)不一致 → false
        // 修复后:verify 用存储的 t=2 算 testHash → 与 stored hash 一致 → true
        assertTrue(
                PasswordHasher.verify(raw, encoded),
                "verify must use params from the stored hash, not class constants");
    }
}
