package com.kwikquant.risk.infrastructure;

import com.kwikquant.risk.domain.RiskPolicy;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RiskPolicyMapper {

    @Select(
            """
            SELECT id, account_id, rule_type, name, params, enabled, created_at, updated_at
            FROM risk_policies WHERE id = #{id}
            """)
    @Results({
        @Result(column = "params", property = "params", typeHandler = JsonMapTypeHandler.class),
    })
    RiskPolicy findById(long id);

    @Select(
            """
            SELECT id, account_id, rule_type, name, params, enabled, created_at, updated_at
            FROM risk_policies WHERE account_id = #{accountId} AND enabled = true
            """)
    @Results({
        @Result(column = "params", property = "params", typeHandler = JsonMapTypeHandler.class),
    })
    List<RiskPolicy> findEnabledByAccountId(long accountId);

    @Select(
            """
            SELECT id, account_id, rule_type, name, params, enabled, created_at, updated_at
            FROM risk_policies WHERE account_id = #{accountId}
            """)
    @Results({
        @Result(column = "params", property = "params", typeHandler = JsonMapTypeHandler.class),
    })
    List<RiskPolicy> findByAccountId(long accountId);

    /**
     * Wave 10 MCP {@code get_risk_rules}（accountId 省略）用：单次查用户全部策略，避免 N+1 循环
     * {@link #findByAccountId}。通过 EXISTS 关联 exchange_accounts 校验 owner（与
     * {@link #updateNameAndParamsWithOwner} 深度防御风格一致）。
     */
    @Select(
            """
            SELECT p.id, p.account_id, p.rule_type, p.name, p.params, p.enabled, p.created_at, p.updated_at
            FROM risk_policies p
            WHERE EXISTS (SELECT 1 FROM exchange_accounts a
                          WHERE a.id = p.account_id AND a.user_id = #{userId})
            """)
    @Results({
        @Result(column = "params", property = "params", typeHandler = JsonMapTypeHandler.class),
    })
    List<RiskPolicy> findByUserId(long userId);

    @Insert(
            """
            INSERT INTO risk_policies (account_id, rule_type, name, params, enabled)
            VALUES (#{accountId}, #{ruleType}, #{name},
                    CAST(#{params, typeHandler=com.kwikquant.risk.infrastructure.JsonMapTypeHandler} AS JSONB),
                    #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(RiskPolicy policy);

    /**
     * @deprecated 不做 owner 深防（无 EXISTS 子查询），仅为 {@code RiskPolicyMapperTest} 集测保留。
     * 生产路径（{@code RiskPolicyManagementService}）必须用 {@link #updateNameAndParamsWithOwner} /
     * {@link #updateEnabledWithOwner}。未来若集测迁移到 seed exchange_account + WithOwner 版本，此方法应删除。
     */
    @Deprecated(forRemoval = true)
    @Update(
            """
            UPDATE risk_policies
            SET name = #{name},
                params = CAST(#{params, typeHandler=com.kwikquant.risk.infrastructure.JsonMapTypeHandler} AS JSONB),
                enabled = #{enabled},
                updated_at = now()
            WHERE id = #{id}
            """)
    int update(RiskPolicy policy);

    /**
     * @deprecated 仅集测保留，生产路径必须用 {@link #deleteByIdWithOwner}。
     */
    @Deprecated(forRemoval = true)
    @Delete("DELETE FROM risk_policies WHERE id = #{id}")
    int deleteById(long id);

    /**
     * 深度防御版：{@code risk_policies} 无 user_id 列，通过 EXISTS 关联 exchange_accounts 校验 owner。
     * 与 LlmApiKey/Strategy 系列 mapper 深度防御一致。Service 层必须走此方法。
     *
     * <p>只 SET name/params（不写 enabled），与 {@link #updateEnabledWithOwner} 各自只更新自己关心的列——
     * 避免"整行读→改一个字段→整行写回"模式下 {@code update()}/{@code toggle()} 并发调用时互相用旧值覆盖
     * 对方刚写入的字段（丢失更新）。
     */
    @Update(
            """
            UPDATE risk_policies
            SET name = #{policy.name},
                params = CAST(#{policy.params, typeHandler=com.kwikquant.risk.infrastructure.JsonMapTypeHandler} AS JSONB),
                updated_at = now()
            WHERE id = #{policy.id}
              AND EXISTS (SELECT 1 FROM exchange_accounts a
                          WHERE a.id = risk_policies.account_id AND a.user_id = #{userId})
            """)
    int updateNameAndParamsWithOwner(@Param("policy") RiskPolicy policy, @Param("userId") long userId);

    /**
     * 深度防御版：只 SET enabled（不写 name/params），见 {@link #updateNameAndParamsWithOwner} 说明。
     */
    @Update(
            """
            UPDATE risk_policies
            SET enabled = #{enabled},
                updated_at = now()
            WHERE id = #{id}
              AND EXISTS (SELECT 1 FROM exchange_accounts a
                          WHERE a.id = risk_policies.account_id AND a.user_id = #{userId})
            """)
    int updateEnabledWithOwner(@Param("id") long id, @Param("enabled") boolean enabled, @Param("userId") long userId);

    @Delete(
            """
            DELETE FROM risk_policies
            WHERE id = #{id}
              AND EXISTS (SELECT 1 FROM exchange_accounts a
                          WHERE a.id = risk_policies.account_id AND a.user_id = #{userId})
            """)
    int deleteByIdWithOwner(@Param("id") long id, @Param("userId") long userId);
}
