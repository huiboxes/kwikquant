package com.kwikquant.risk.infrastructure;

import com.kwikquant.risk.domain.RiskDecision;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RiskDecisionMapper {

    @Select(
            """
            SELECT id, request_id, order_id, account_id, verdict, rule_results, created_at
            FROM risk_decisions WHERE request_id = #{requestId}
            """)
    @Results({
        @Result(column = "rule_results", property = "ruleResults", typeHandler = JsonRuleResultListTypeHandler.class),
    })
    RiskDecision findByRequestId(String requestId);

    @Select(
            """
            SELECT id, request_id, order_id, account_id, verdict, rule_results, created_at
            FROM risk_decisions WHERE order_id = #{orderId}
            """)
    @Results({
        @Result(column = "rule_results", property = "ruleResults", typeHandler = JsonRuleResultListTypeHandler.class),
    })
    RiskDecision findByOrderId(long orderId);

    @Insert(
            """
            INSERT INTO risk_decisions (request_id, order_id, account_id, verdict, rule_results)
            VALUES (#{requestId}, #{orderId}, #{accountId}, #{verdict},
                    CAST(#{ruleResults, typeHandler=com.kwikquant.risk.infrastructure.JsonRuleResultListTypeHandler} AS JSONB))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(RiskDecision decision);

    @Select(
            """
            <script>
            SELECT id, request_id, order_id, account_id, verdict, rule_results, created_at
            FROM risk_decisions
            WHERE account_id = #{accountId}
            <if test="verdict != null"> AND verdict = #{verdict}</if>
            <if test="startTime != null"> AND created_at &gt;= #{startTime}</if>
            <if test="endTime != null"> AND created_at &lt;= #{endTime}</if>
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results({
        @Result(column = "rule_results", property = "ruleResults", typeHandler = JsonRuleResultListTypeHandler.class),
    })
    List<RiskDecision> findByAccount(
            @Param("accountId") long accountId,
            @Param("verdict") String verdict,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select(
            """
            <script>
            SELECT COUNT(*) FROM risk_decisions
            WHERE account_id = #{accountId}
            <if test="verdict != null"> AND verdict = #{verdict}</if>
            <if test="startTime != null"> AND created_at &gt;= #{startTime}</if>
            <if test="endTime != null"> AND created_at &lt;= #{endTime}</if>
            </script>
            """)
    long countByAccount(
            @Param("accountId") long accountId,
            @Param("verdict") String verdict,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Delete("DELETE FROM risk_decisions WHERE created_at < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
