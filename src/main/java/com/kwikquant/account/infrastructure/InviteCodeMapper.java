package com.kwikquant.account.infrastructure;

import com.kwikquant.account.domain.InviteCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 邀请码 Mapper(V20 migration,MyBatis annotation 风格,与 UserMapper 一致)。
 *
 * <p>{@link #incrementUsedCount} 用乐观锁消费(WHERE used_count &lt; max_uses AND enabled = TRUE),
 * 并发安全不会超卖:返回 1 = 消费成功;0 = 已用尽/禁用(调用方抛 {@code InvalidInviteCodeException})。
 */
@Mapper
public interface InviteCodeMapper {

    @Select(
            """
            SELECT code, max_uses, used_count, expires_at, enabled, created_at
            FROM invite_codes WHERE code = #{code}
            """)
    InviteCode findByCode(String code);

    @Update(
            """
            UPDATE invite_codes
            SET used_count = used_count + 1
            WHERE code = #{code}
              AND enabled = TRUE
              AND used_count < max_uses
            """)
    int incrementUsedCount(String code);
}
