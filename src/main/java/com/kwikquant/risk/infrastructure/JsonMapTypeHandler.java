package com.kwikquant.risk.infrastructure;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * MyBatis type handler that serializes {@code Map<String, String>} to/from PostgreSQL JSONB.
 *
 * <p>Uses plain string serialization with {@code CAST(? AS JSONB)} in SQL to avoid compile-time
 * dependency on {@code org.postgresql.util.PGobject} (the PostgreSQL driver is runtime-scoped).
 */
@MappedTypes(Map.class)
public class JsonMapTypeHandler extends BaseTypeHandler<Map<String, String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, String> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JacksonException e) {
            throw new SQLException("Failed to serialize params to JSON", e);
        }
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJson(rs.getString(columnName));
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJson(rs.getString(columnIndex));
    }

    @Override
    public Map<String, String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJson(cs.getString(columnIndex));
    }

    private Map<String, String> parseJson(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JacksonException e) {
            throw new SQLException("Failed to deserialize JSON to Map", e);
        }
    }
}
