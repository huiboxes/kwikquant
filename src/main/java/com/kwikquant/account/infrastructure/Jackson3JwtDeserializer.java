package com.kwikquant.account.infrastructure;

import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.Deserializer;
import java.io.Reader;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * jjwt 的 Jackson 3 JSON 反序列化适配器，与 {@link Jackson3JwtSerializer} 配对，
 * 经 {@code Jwts.parser().json(...)} 注入。背景同 {@link Jackson3JwtSerializer}。
 */
public final class Jackson3JwtDeserializer implements Deserializer<Map<String, ?>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, ?>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public Map<String, ?> deserialize(byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, MAP_TYPE);
        } catch (JacksonException e) {
            throw new DeserializationException("Unable to deserialize JWT JSON payload", e);
        }
    }

    @Override
    public Map<String, ?> deserialize(Reader reader) {
        try {
            return MAPPER.readValue(reader, MAP_TYPE);
        } catch (JacksonException e) {
            throw new DeserializationException("Unable to deserialize JWT JSON payload", e);
        }
    }
}
