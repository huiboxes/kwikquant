package com.kwikquant.risk.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kwikquant.risk.domain.RiskRuleType;
import com.kwikquant.risk.domain.RuleResult;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito unit tests for {@link JsonRuleResultListTypeHandler}.
 *
 * <p>Mirrors {@link JsonMapTypeHandlerTest}: covers the three {@code getNullableResult}
 * overloads, the null-result branch, and the malformed-JSON → {@link SQLException} branch,
 * plus {@code setNonNullParameter} serialization.
 */
class JsonRuleResultListTypeHandlerTest {

    private final JsonRuleResultListTypeHandler handler = new JsonRuleResultListTypeHandler();

    private static final String JSON = "[{\"ruleType\":\"MAX_NOTIONAL\",\"passed\":false,\"reason\":\"too big\"}]";

    @Test
    void setNonNullParameter_writesJsonString() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        List<RuleResult> results = List.of(new RuleResult(RiskRuleType.MAX_NOTIONAL, false, "too big"));

        handler.setNonNullParameter(ps, 1, results, JdbcType.OTHER);

        verify(ps).setString(eq(1), contains("MAX_NOTIONAL"));
    }

    @Test
    void getNullableResultByColumnName_returnsParsedList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("rule_results")).thenReturn(JSON);

        List<RuleResult> result = handler.getNullableResult(rs, "rule_results");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
        assertThat(result.getFirst().passed()).isFalse();
        assertThat(result.getFirst().reason()).isEqualTo("too big");
    }

    @Test
    void getNullableResultByColumnIndex_returnsParsedList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(3)).thenReturn(JSON);

        List<RuleResult> result = handler.getNullableResult(rs, 3);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().ruleType()).isEqualTo(RiskRuleType.MAX_NOTIONAL);
    }

    @Test
    void getNullableResultFromCallableStatement_returnsParsedList() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn(JSON);

        List<RuleResult> result = handler.getNullableResult(cs, 1);

        assertThat(result).hasSize(1);
    }

    @Test
    void getNullableResult_whenNull_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("rule_results")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "rule_results")).isNull();
    }

    @Test
    void getNullableResult_whenMalformedJson_throwsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("rule_results")).thenReturn("[not valid json");

        assertThatThrownBy(() -> handler.getNullableResult(rs, "rule_results"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Failed to deserialize JSON to List<RuleResult>");
    }
}
