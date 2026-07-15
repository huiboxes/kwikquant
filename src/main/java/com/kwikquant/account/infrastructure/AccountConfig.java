package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.ApiKeyEncryptor;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AccountConfig {

    @Bean
    JwtProvider jwtProvider(
            @Value("${kwikquant.jwt.secret}") String jwtSecret,
            @Value("${kwikquant.jwt.access-token-ttl:15m}") Duration accessTokenTtl,
            @Value("${kwikquant.jwt.refresh-token-ttl:7d}") Duration refreshTokenTtl) {
        SecretKey key = decodeKey(jwtSecret);
        return new JwtProvider(key, accessTokenTtl, refreshTokenTtl);
    }

    @Bean
    byte[] encryptionKey(@Value("${kwikquant.encryption.key}") String base64Key) {
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != ApiKeyEncryptor.AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("ENCRYPTION_KEY must be " + ApiKeyEncryptor.AES_256_KEY_BYTES
                    + " bytes (AES-256), got " + key.length);
        }
        return key;
    }

    private SecretKey decodeKey(String base64Secret) {
        byte[] decoded = Base64.getDecoder().decode(base64Secret);
        if (decoded.length < ApiKeyEncryptor.AES_256_KEY_BYTES) {
            throw new IllegalArgumentException("JWT_SECRET must be at least " + ApiKeyEncryptor.AES_256_KEY_BYTES
                    + " bytes (256 bits), got " + decoded.length);
        }
        return new SecretKeySpec(decoded, "HmacSHA256");
    }
}
