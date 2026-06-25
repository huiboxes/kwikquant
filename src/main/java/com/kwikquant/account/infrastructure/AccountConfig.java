package com.kwikquant.account.infrastructure;

import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    Map<Integer, byte[]> encryptionKeys(@Value("${kwikquant.encryption.keys}") String keysConfig) {
        Map<Integer, byte[]> keyMap = new LinkedHashMap<>();
        for (String entry : keysConfig.split(",")) {
            String[] parts = entry.strip().split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid encryption keys format. Expected 'v1:base64key,v2:base64key', got: " + entry);
            }
            int version = Integer.parseInt(parts[0].replace("v", ""));
            byte[] key = Base64.getDecoder().decode(parts[1]);
            if (key.length != 32) {
                throw new IllegalArgumentException(
                        "Encryption key v" + version + " must be 32 bytes (AES-256), got " + key.length);
            }
            keyMap.put(version, key);
        }
        return Collections.unmodifiableMap(keyMap);
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
