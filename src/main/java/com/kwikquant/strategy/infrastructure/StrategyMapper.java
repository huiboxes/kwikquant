package com.kwikquant.strategy.infrastructure;

import com.kwikquant.strategy.domain.StrategyDefinition;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StrategyMapper {

    @Insert(
            """
            INSERT INTO strategies (user_id, name, description, symbol, exchange,
                                    market_type, interval_value, status, parameters, deleted)
            VALUES (#{userId}, #{name}, #{description}, #{symbol}, #{exchange},
                    #{marketType}, #{intervalValue}, #{status}, CAST(#{parameters} AS JSONB), #{deleted})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(StrategyDefinition strategy);

    @Select(
            """
            SELECT id, user_id, name, description, symbol, exchange,
                   market_type, interval_value, status, parameters, version, exchange_account_id, deleted,
                   created_at, updated_at, stop_reason
            FROM strategies WHERE id = #{id} AND deleted = FALSE
            """)
    @Results({
        @Result(column = "user_id", property = "userId"),
        @Result(column = "market_type", property = "marketType"),
        @Result(column = "interval_value", property = "intervalValue"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "exchange_account_id", property = "exchangeAccountId"),
        @Result(column = "stop_reason", property = "stopReason")
    })
    StrategyDefinition findById(@Param("id") long id);

    /** 按状态查询未软删的策略（应用重启 reconcile 用）。 */
    @Select(
            """
            SELECT id, user_id, name, description, symbol, exchange,
                   market_type, interval_value, status, parameters, version, exchange_account_id, deleted,
                   created_at, updated_at, stop_reason
            FROM strategies WHERE status = #{status} AND deleted = FALSE
            """)
    @Results({
        @Result(column = "user_id", property = "userId"),
        @Result(column = "market_type", property = "marketType"),
        @Result(column = "interval_value", property = "intervalValue"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "exchange_account_id", property = "exchangeAccountId"),
        @Result(column = "stop_reason", property = "stopReason")
    })
    List<StrategyDefinition> findByStatus(@Param("status") String status);

    @Select(
            """
            SELECT id, user_id, name, description, symbol, exchange,
                   market_type, interval_value, status, parameters, version, exchange_account_id, deleted,
                   created_at, updated_at, stop_reason
            FROM strategies WHERE user_id = #{userId} AND deleted = FALSE
            ORDER BY created_at DESC
            """)
    @Results({
        @Result(column = "user_id", property = "userId"),
        @Result(column = "market_type", property = "marketType"),
        @Result(column = "interval_value", property = "intervalValue"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "exchange_account_id", property = "exchangeAccountId"),
        @Result(column = "stop_reason", property = "stopReason")
    })
    List<StrategyDefinition> findByUserId(@Param("userId") long userId);

    /** 按更新时间倒序查询用户策略（last-edited 端点用）。 */
    @Select(
            """
            SELECT id, user_id, name, description, symbol, exchange,
                   market_type, interval_value, status, parameters, version, exchange_account_id, deleted,
                   created_at, updated_at, stop_reason
            FROM strategies WHERE user_id = #{userId} AND deleted = FALSE
            ORDER BY updated_at DESC
            LIMIT #{limit}
            """)
    @Results({
        @Result(column = "user_id", property = "userId"),
        @Result(column = "market_type", property = "marketType"),
        @Result(column = "interval_value", property = "intervalValue"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt"),
        @Result(column = "exchange_account_id", property = "exchangeAccountId"),
        @Result(column = "stop_reason", property = "stopReason")
    })
    List<StrategyDefinition> findByUserIdOrderByUpdatedAtDesc(@Param("userId") long userId, @Param("limit") int limit);

    /**
     * CAS 状态更新：仅当当前 status 匹配且属于指定 user 时才更新。返回影响行数（0=冲突或越权，1=成功）。
     *
     * <p>深度防御：SQL WHERE 层再做一次 {@code user_id} 校验，避免调用方漏做 {@code getOwned} 时越权。
     */
    @Update(
            """
            UPDATE strategies
            SET status = #{newStatus}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId}
              AND status = #{expectedStatus} AND deleted = FALSE
            """)
    int updateStatus(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus);

    /**
     * CAS 状态更新 + 停止原因:仅当当前 status 匹配且属于指定 user 时更新 status + stop_reason。
     * markError 存 reason(ERROR);start/restart 传 null(清旧 stop_reason);stop/pause/ready 用 updateStatus(不动 stop_reason)。
     */
    @Update(
            """
            UPDATE strategies
            SET status = #{newStatus}, stop_reason = #{stopReason}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId}
              AND status = #{expectedStatus} AND deleted = FALSE
            """)
    int updateStatusWithReason(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("stopReason") String stopReason);

    /** {@code StrategyDefinition.userId} 已在实体中，MyBatis 通过 #{userId} 自动取到。 */
    @Update(
            """
            UPDATE strategies
            SET name = #{name}, description = #{description},
                symbol = #{symbol}, exchange = #{exchange},
                market_type = #{marketType}, interval_value = #{intervalValue},
                parameters = CAST(#{parameters} AS JSONB), version = #{version},
                updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND deleted = FALSE
            """)
    int update(StrategyDefinition strategy);

    /** 启动策略时绑定账户(去 UNIQUE 后同 exchange 多账户,start 选账户;worker token 绑 accountId)。 */
    @Update(
            """
            UPDATE strategies
            SET exchange_account_id = #{exchangeAccountId}, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND deleted = FALSE
            """)
    int updateExchangeAccountId(
            @Param("id") long id, @Param("userId") long userId, @Param("exchangeAccountId") Long exchangeAccountId);

    @Update(
            """
            UPDATE strategies SET deleted = TRUE, updated_at = now()
            WHERE id = #{id} AND user_id = #{userId} AND deleted = FALSE
            """)
    int softDelete(@Param("id") long id, @Param("userId") long userId);
}
