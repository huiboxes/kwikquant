package com.kwikquant.trading.infrastructure;

import com.kwikquant.trading.domain.Position;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PositionMapper {

    @Insert(
            """
            INSERT INTO positions (account_id, symbol, side, qty, avg_entry_price, realized_pnl, version)
            VALUES (#{accountId}, #{symbol}, #{side}, #{qty}, #{avgEntryPrice}, #{realizedPnl}, #{version})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Position position);

    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   version, created_at, updated_at
            FROM positions
            WHERE account_id = #{accountId} AND symbol = #{symbol}
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "avg_entry_price", property = "avgEntryPrice"),
        @Result(column = "realized_pnl", property = "realizedPnl"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    Position findByAccountAndSymbol(@Param("accountId") long accountId, @Param("symbol") String symbol);

    @Update(
            """
            UPDATE positions
            SET side = #{side},
                qty = #{qty},
                avg_entry_price = #{avgEntryPrice},
                realized_pnl = #{realizedPnl},
                version = version + 1,
                updated_at = now()
            WHERE id = #{id} AND version = #{version}
            """)
    int casUpdate(Position position);

    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   version, created_at, updated_at
            FROM positions
            WHERE account_id = #{accountId}
            ORDER BY symbol ASC
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "avg_entry_price", property = "avgEntryPrice"),
        @Result(column = "realized_pnl", property = "realizedPnl"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<Position> findByAccount(@Param("accountId") long accountId);
}
