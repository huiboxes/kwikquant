package com.kwikquant.risk.infrastructure;

import com.kwikquant.risk.domain.RuleResult;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * MyBatis type handler that serializes {@code List<RuleResult>} to/from PostgreSQL JSONB.
 *
 * <p>Uses plain string serialization with {@code CAST(? AS JSONB)} in SQL to avoid compile-time
 * dependency on {@code org.postgresql.util.PGobject} (the PostgreSQL driver is runtime-scoped).
 */
@MappedTypes(List.class)
public class JsonRuleResultListTypeHandler extends BaseTypeHandler<List<RuleResult>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<RuleResult>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<RuleResult> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JacksonException e) {
            throw new SQLException("Failed to serialize rule results to JSON", e);
        }
    }

    @Override
    public List<RuleResult> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJson(rs.getString(columnName));
    }

    @Override
    public List<RuleResult> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJson(rs.getString(columnIndex));
    }

    @Override
    public List<RuleResult> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJson(cs.getString(columnIndex));
    }

    private List<RuleResult> parseJson(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JacksonException e) {
            throw new SQLException("Failed to deserialize JSON to List<RuleResult>", e);
        }
    }
}
