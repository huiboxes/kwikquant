package com.kwikquant.account.infrastructure;

import io.jsonwebtoken.io.SerializationException;
import io.jsonwebtoken.io.Serializer;
import java.io.OutputStream;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * jjwt 的 Jackson 3 JSON 序列化适配器。
 *
 * <p>jjwt 官方 {@code jjwt-jackson} 模块仅支持 Jackson 2.x（jwtk/jjwt#1029 尚未落地 Jackson 3 适配器）。
 * 项目自身已全量 Jackson 3，用本适配器经 {@code Jwts.builder().json(...)} 注入，
 * JWT 编解码不再依赖 Jackson 2.x；运行时残留的 2.x（ccxt 硬依赖）由 jackson-2-bom 钉定安全版本
 * （CVE-2026-54515，见 pom.xml）。
 */
public final class Jackson3JwtSerializer implements Serializer<Map<String, ?>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public byte[] serialize(Map<String, ?> map) {
        try {
            return MAPPER.writeValueAsBytes(map);
        } catch (JacksonException e) {
            throw new SerializationException("Unable to serialize JWT JSON payload", e);
        }
    }

    @Override
    public void serialize(Map<String, ?> map, OutputStream out) {
        try {
            MAPPER.writeValue(out, map);
        } catch (JacksonException e) {
            throw new SerializationException("Unable to serialize JWT JSON payload", e);
        }
    }
}
