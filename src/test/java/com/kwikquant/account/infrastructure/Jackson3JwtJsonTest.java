package com.kwikquant.account.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.SerializationException;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class Jackson3JwtJsonTest {

    private final Jackson3JwtSerializer serializer = new Jackson3JwtSerializer();
    private final Jackson3JwtDeserializer deserializer = new Jackson3JwtDeserializer();

    @Test
    void roundTripPreservesClaimTypes() {
        // exp 取超 int 上限的值，确保 Jackson 以 Long 往返，类型不漂移
        Map<String, ?> original = Map.of("sub", "42", "username", "alice", "exp", 9_790_000_000L, "admin", true);
        Map<String, ?> parsed = deserializer.deserialize(serializer.serialize(original));
        assertEquals(original, parsed);
    }

    @Test
    void serializeToStreamMatchesByteVariant() {
        Map<String, ?> claims = Map.of("sub", "7");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        serializer.serialize(claims, out);
        assertArrayEquals(serializer.serialize(claims), out.toByteArray());
    }

    @Test
    void deserializeFromReaderMatchesByteVariant() {
        byte[] bytes = serializer.serialize(Map.of("sub", "7"));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(deserializer.deserialize(bytes), deserializer.deserialize(new StringReader(json)));
    }

    @Test
    void malformedJsonThrowsDeserializationException() {
        byte[] bad = "{not json".getBytes(StandardCharsets.UTF_8);
        assertThrows(DeserializationException.class, () -> deserializer.deserialize(bad));
        assertThrows(DeserializationException.class, () -> deserializer.deserialize(new StringReader("{not json")));
    }

    @Test
    void failingBeanThrowsSerializationException() {
        assertThrows(SerializationException.class, () -> serializer.serialize(Map.of("k", new ThrowingBean())));
    }

    @Test
    void endToEndJwtWithJackson3Adapter() {
        SecretKey key = Jwts.SIG.HS256.key().build();
        String token = Jwts.builder()
                .subject("42")
                .claim("username", "alice")
                .json(serializer)
                .signWith(key)
                .compact();
        var claims = Jwts.parser()
                .verifyWith(key)
                .json(deserializer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals("42", claims.getSubject());
        assertEquals("alice", claims.get("username"));
    }

    /** getter 抛异常的 Bean，保证序列化必失败，覆盖 SerializationException 分支。 */
    public static class ThrowingBean {
        public String getBoom() {
            throw new IllegalStateException("boom");
        }
    }
}
