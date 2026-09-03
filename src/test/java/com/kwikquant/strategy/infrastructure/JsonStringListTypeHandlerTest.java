package com.kwikquant.strategy.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

/** Pure-Mockito unit tests for {@link JsonStringListTypeHandler}. */
class JsonStringListTypeHandlerTest {

    private final JsonStringListTypeHandler handler = new JsonStringListTypeHandler();

    @Test
    void setNonNullParameter_writesJsonString() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, List.of("BTC/USDT", "ETH/USDT"), JdbcType.OTHER);

        verify(ps).setString(eq(1), contains("BTC/USDT"));
    }

    @Test
    void getNullableResultByColumnName_returnsParsedList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("symbols")).thenReturn("[\"BTC/USDT\",\"ETH/USDT\"]");

        assertThat(handler.getNullableResult(rs, "symbols")).containsExactly("BTC/USDT", "ETH/USDT");
    }

    @Test
    void getNullableResultByColumnIndex_returnsParsedList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(2)).thenReturn("[\"BTC/USDT\"]");

        assertThat(handler.getNullableResult(rs, 2)).containsExactly("BTC/USDT");
    }

    @Test
    void getNullableResultFromCallableStatement_returnsParsedList() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn("[\"BTC/USDT\"]");

        assertThat(handler.getNullableResult(cs, 1)).containsExactly("BTC/USDT");
    }

    @Test
    void getNullableResult_whenNull_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("symbols")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "symbols")).isNull();
    }

    @Test
    void getNullableResult_whenMalformedJson_throwsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("symbols")).thenReturn("{not valid json");

        assertThatThrownBy(() -> handler.getNullableResult(rs, "symbols"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Failed to deserialize JSON to List");
    }
}
