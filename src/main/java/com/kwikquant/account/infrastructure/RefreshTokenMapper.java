package com.kwikquant.account.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefreshTokenMapper {

    @Insert(
            """
            INSERT INTO refresh_tokens (jti, user_id, expires_at)
            VALUES (#{jti}, #{userId}, #{expiresAt})
            """)
    void insert(RefreshTokenRow row);

    @Select(
            """
            SELECT id, jti, user_id, revoked_at, expires_at, created_at
            FROM refresh_tokens WHERE jti = #{jti}
            """)
    RefreshTokenRow findByJti(String jti);

    @Update(
            """
            UPDATE refresh_tokens SET revoked_at = now()
            WHERE jti = #{jti} AND revoked_at IS NULL
            """)
    int revokeByJti(String jti);

    @Update(
            """
            UPDATE refresh_tokens SET revoked_at = now()
            WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllByUserId(long userId);

    @Select(
            """
            SELECT id, jti, user_id, revoked_at, expires_at, created_at
            FROM refresh_tokens
            WHERE user_id = #{userId} AND revoked_at IS NULL AND expires_at > now()
            """)
    List<RefreshTokenRow> findActiveByUserId(long userId);

    @Delete(
            """
            DELETE FROM refresh_tokens
            WHERE (revoked_at IS NOT NULL OR expires_at < now())
            """)
    int deleteExpiredAndRevoked();

    record RefreshTokenRow(Long id, String jti, long userId, Instant revokedAt, Instant expiresAt, Instant createdAt) {
        public RefreshTokenRow(String jti, long userId, Instant expiresAt) {
            this(null, jti, userId, null, expiresAt, null);
        }

        public boolean isRevoked() {
            return revokedAt != null;
        }

        public boolean isExpired() {
            return expiresAt.isBefore(Instant.now());
        }
    }
}
