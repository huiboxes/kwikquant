package com.kwikquant.risk.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito unit tests for {@link JsonMapTypeHandler}.
 *
 * <p>The integration tests ({@code RiskPolicyMapperTest}) exercise the handler via MyBatis but
 * only through the {@code (ResultSet, columnName)} overload with valid JSON. These unit tests
 * cover the remaining overloads, the null-result branch, and the malformed-JSON →
 * {@link SQLException} branch.
 */
class JsonMapTypeHandlerTest {

    private final JsonMapTypeHandler handler = new JsonMapTypeHandler();

    @Test
    void setNonNullParameter_writesJsonString() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, Map.of("maxNotionalUsdt", "50000"), JdbcType.OTHER);

        verify(ps).setString(eq(1), contains("maxNotionalUsdt"));
    }

    @Test
    void getNullableResultByColumnName_returnsParsedMap() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("params")).thenReturn("{\"k\":\"v\"}");

        Map<String, String> result = handler.getNullableResult(rs, "params");

        assertThat(result).containsEntry("k", "v");
    }

    @Test
    void getNullableResultByColumnIndex_returnsParsedMap() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(2)).thenReturn("{\"k\":\"v\"}");

        Map<String, String> result = handler.getNullableResult(rs, 2);

        assertThat(result).containsEntry("k", "v");
    }

    @Test
    void getNullableResultFromCallableStatement_returnsParsedMap() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn("{\"k\":\"v\"}");

        Map<String, String> result = handler.getNullableResult(cs, 1);

        assertThat(result).containsEntry("k", "v");
    }

    @Test
    void getNullableResult_whenNull_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("params")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "params")).isNull();
    }

    @Test
    void getNullableResult_whenMalformedJson_throwsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("params")).thenReturn("{not valid json");

        assertThatThrownBy(() -> handler.getNullableResult(rs, "params"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Failed to deserialize JSON to Map");
    }
}
