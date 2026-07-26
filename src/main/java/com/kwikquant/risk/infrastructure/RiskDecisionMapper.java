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

    /**
     * 跨账户分页查询当前用户所有账户的风控决策。{@code risk_decisions} 无 user_id 列,
     * 通过 EXISTS 关联 {@code exchange_accounts} 校验 owner——与
     * {@link RiskPolicyMapper#findByUserId} / {@code updateNameAndParamsWithOwner} 深度防御
     * 风格一致。风控页跨账户总览(原型 {@code RiskPage.jsx} 的 {@code data.riskAudit})
     * 走此路径,避免前端为查决策审计而先选账户。
     */
    @Select(
            """
            <script>
            SELECT d.id, d.request_id, d.order_id, d.account_id, d.verdict, d.rule_results, d.created_at
            FROM risk_decisions d
            WHERE EXISTS (SELECT 1 FROM exchange_accounts a
                          WHERE a.id = d.account_id AND a.user_id = #{userId})
            <if test="verdict != null"> AND d.verdict = #{verdict}</if>
            <if test="startTime != null"> AND d.created_at &gt;= #{startTime}</if>
            <if test="endTime != null"> AND d.created_at &lt;= #{endTime}</if>
            ORDER BY d.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results({
        @Result(column = "rule_results", property = "ruleResults", typeHandler = JsonRuleResultListTypeHandler.class),
    })
    List<RiskDecision> findByUserId(
            @Param("userId") long userId,
            @Param("verdict") String verdict,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /** 跨账户决策计数,WHERE 与 {@link #findByUserId} 一致(分页 total 用)。 */
    @Select(
            """
            <script>
            SELECT COUNT(*) FROM risk_decisions d
            WHERE EXISTS (SELECT 1 FROM exchange_accounts a
                          WHERE a.id = d.account_id AND a.user_id = #{userId})
            <if test="verdict != null"> AND d.verdict = #{verdict}</if>
            <if test="startTime != null"> AND d.created_at &gt;= #{startTime}</if>
            <if test="endTime != null"> AND d.created_at &lt;= #{endTime}</if>
            </script>
            """)
    long countByUserId(
            @Param("userId") long userId,
            @Param("verdict") String verdict,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Delete("DELETE FROM risk_decisions WHERE created_at < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
