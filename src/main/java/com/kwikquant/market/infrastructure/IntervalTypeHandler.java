package com.kwikquant.market.infrastructure;

import com.kwikquant.shared.types.Interval;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(Interval.class)
public class IntervalTypeHandler extends BaseTypeHandler<Interval> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Interval parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter.ccxtValue());
    }

    @Override
    public Interval getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String val = rs.getString(columnName);
        return val != null ? Interval.fromCcxt(val) : null;
    }

    @Override
    public Interval getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String val = rs.getString(columnIndex);
        return val != null ? Interval.fromCcxt(val) : null;
    }

    @Override
    public Interval getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String val = cs.getString(columnIndex);
        return val != null ? Interval.fromCcxt(val) : null;
    }
}
