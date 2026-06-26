package com.kwikquant.account.domain;

import java.security.SecureRandom;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

public final class PasswordHasher {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65536;
    private static final int PARALLELISM = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);

        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KB)
                .withParallelism(PARALLELISM)
                .build();

        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] hash = new byte[HASH_LENGTH];
        gen.generateBytes(rawPassword.toCharArray(), hash);

        return encode(salt, hash);
    }

    public static boolean verify(String rawPassword, String encoded) {
        DecodedHash decoded = decode(encoded);

        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(decoded.salt)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KB)
                .withParallelism(PARALLELISM)
                .build();

        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] testHash = new byte[HASH_LENGTH];
        gen.generateBytes(rawPassword.toCharArray(), testHash);

        return constantTimeEquals(decoded.hash, testHash);
    }

    private static String encode(byte[] salt, byte[] hash) {
        return "$argon2id$v=19$m=" + MEMORY_KB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$"
                + java.util.Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + java.util.Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    private static DecodedHash decode(String encoded) {
        String[] parts = encoded.split("\\$");
        if (parts.length != 6) {
            throw new IllegalArgumentException("invalid argon2id hash format");
        }
        byte[] salt = java.util.Base64.getDecoder().decode(parts[4]);
        byte[] hash = java.util.Base64.getDecoder().decode(parts[5]);
        return new DecodedHash(salt, hash);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private record DecodedHash(byte[] salt, byte[] hash) {}
}
