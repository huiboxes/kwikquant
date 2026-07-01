package com.kwikquant.account.domain;

import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

public final class PasswordHasher {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65536;
    private static final int PARALLELISM = 1;
    private static final int VERSION = 19; // Argon2 v1.3 (ARGON2_VERSION_13)
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] hash = compute(rawPassword, salt, ITERATIONS, MEMORY_KB, PARALLELISM);
        return encode(salt, hash, ITERATIONS, MEMORY_KB, PARALLELISM);
    }

    /**
     * 使用哈希串中存储的 m/t/p 参数进行验证,而非类常量。
     * 这样调参后老用户的密码仍可验证,且支持"下次登录时用旧参数验证 → 用新参数 rehash"的迁移路径。
     */
    public static boolean verify(String rawPassword, String encoded) {
        DecodedHash decoded = decode(encoded);
        byte[] testHash = compute(rawPassword, decoded.salt, decoded.iterations, decoded.memory, decoded.parallelism);
        return constantTimeEquals(decoded.hash, testHash);
    }

    private static byte[] compute(String rawPassword, byte[] salt, int iterations, int memory, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(iterations)
                .withMemoryAsKB(memory)
                .withParallelism(parallelism)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] hash = new byte[HASH_LENGTH];
        gen.generateBytes(rawPassword.toCharArray(), hash);
        return hash;
    }

    private static String encode(byte[] salt, byte[] hash, int iterations, int memory, int parallelism) {
        return "$argon2id$v=" + VERSION + "$m=" + memory + ",t=" + iterations + ",p=" + parallelism + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    private static DecodedHash decode(String encoded) {
        String[] parts = encoded.split("\\$");
        if (parts.length != 6) {
            throw new IllegalArgumentException("invalid argon2id hash format");
        }
        if (!"argon2id".equals(parts[1])) {
            throw new IllegalArgumentException("unsupported argon2 variant: " + parts[1]);
        }
        int version = parseSegment(parts[2], "v");
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported argon2 version: v=" + version);
        }
        int[] mtp = parseParams(parts[3]);
        byte[] salt = Base64.getDecoder().decode(parts[4]);
        byte[] hash = Base64.getDecoder().decode(parts[5]);
        return new DecodedHash(salt, hash, mtp[0], mtp[1], mtp[2]);
    }

    private static int[] parseParams(String s) {
        // 格式: m=65536,t=3,p=1
        int m = 0, t = 0, p = 0;
        for (String kv : s.split(",")) {
            String[] eq = kv.split("=", 2);
            if (eq.length != 2) {
                throw new IllegalArgumentException("invalid argon2 params: " + s);
            }
            int v;
            try {
                v = Integer.parseInt(eq[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid argon2 param value: " + kv, e);
            }
            switch (eq[0]) {
                case "m" -> m = v;
                case "t" -> t = v;
                case "p" -> p = v;
                default -> {
                    /* 忽略未知参数键,前向兼容 */
                }
            }
        }
        if (m <= 0 || t <= 0 || p <= 0) {
            throw new IllegalArgumentException("incomplete argon2 params: " + s);
        }
        return new int[] {m, t, p};
    }

    private static int parseSegment(String segment, String key) {
        if (!segment.startsWith(key + "=")) {
            throw new IllegalArgumentException("invalid argon2 segment: " + segment);
        }
        try {
            return Integer.parseInt(segment.substring(key.length() + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid argon2 segment value: " + segment, e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private record DecodedHash(byte[] salt, byte[] hash, int memory, int iterations, int parallelism) {}
}
