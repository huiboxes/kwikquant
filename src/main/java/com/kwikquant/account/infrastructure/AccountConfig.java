package com.kwikquant.account.infrastructure;

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
        if (key.length != 32) {
            throw new IllegalArgumentException("ENCRYPTION_KEY must be 32 bytes (AES-256), got " + key.length);
        }
        return key;
    }

    private SecretKey decodeKey(String base64Secret) {
        byte[] decoded = Base64.getDecoder().decode(base64Secret);
        if (decoded.length < 32) {
            throw new IllegalArgumentException(
                    "JWT_SECRET must be at least 32 bytes (256 bits), got " + decoded.length);
        }
        return new SecretKeySpec(decoded, "HmacSHA256");
    }
}
