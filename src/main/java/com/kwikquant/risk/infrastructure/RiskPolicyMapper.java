package com.kwikquant.risk.infrastructure;

import com.kwikquant.risk.domain.RiskPolicy;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
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

    @Insert(
            """
            INSERT INTO risk_policies (account_id, rule_type, name, params, enabled)
            VALUES (#{accountId}, #{ruleType}, #{name},
                    CAST(#{params, typeHandler=com.kwikquant.risk.infrastructure.JsonMapTypeHandler} AS JSONB),
                    #{enabled})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(RiskPolicy policy);

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

    @Delete("DELETE FROM risk_policies WHERE id = #{id}")
    int deleteById(long id);
}
