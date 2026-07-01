package com.kwikquant.market.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kwikquant.shared.types.Interval;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class IntervalTypeHandlerTest {
    private final IntervalTypeHandler handler = new IntervalTypeHandler();

    @Test
    void setNonNullParameter_writesCcxtValue() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, Interval._1h, JdbcType.VARCHAR);
        verify(ps).setString(1, Interval._1h.ccxtValue());
    }

    @Test
    void getNullableResult_byColumnName_parsesCcxt() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("interval")).thenReturn(Interval._4h.ccxtValue());
        Interval result = handler.getNullableResult(rs, "interval");
        assertThat(result).isEqualTo(Interval._4h);
    }

    @Test
    void getNullableResult_byColumnIndex_parsesCcxt() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(2)).thenReturn(Interval._1d.ccxtValue());
        Interval result = handler.getNullableResult(rs, 2);
        assertThat(result).isEqualTo(Interval._1d);
    }

    @Test
    void getNullableResult_callableStatement_parsesCcxt() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn(Interval._15m.ccxtValue());
        Interval result = handler.getNullableResult(cs, 1);
        assertThat(result).isEqualTo(Interval._15m);
    }

    @Test
    void getNullableResult_whenNull_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("interval")).thenReturn(null);
        assertThat(handler.getNullableResult(rs, "interval")).isNull();
    }
}
