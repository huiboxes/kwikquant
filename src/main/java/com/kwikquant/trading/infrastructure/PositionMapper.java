package com.kwikquant.trading.infrastructure;

import com.kwikquant.shared.types.MarginMode;
import com.kwikquant.trading.domain.Position;
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
import org.apache.ibatis.type.EnumTypeHandler;

@Mapper
public interface PositionMapper {

    @Insert(
            """
            INSERT INTO positions (account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                leverage, margin_mode, position_side, liquidation_price, maint_margin, frozen_amount, version)
            VALUES (#{accountId}, #{symbol}, #{side}, #{qty}, #{avgEntryPrice}, #{realizedPnl},
                #{leverage}, #{marginMode}, #{positionSide}, #{liquidationPrice}, #{maintMargin}, #{frozenAmount}, #{version})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Position position);

    /**
     * 旧 SPOT 兼容查询(account+symbol 单行,SPOT 持仓唯一)。
     * <p>PERP 双向持仓同 account+symbol 可多行(SPOT+PERP-LONG+PERP-SHORT),用 {@link #findByAccountSymbolPosition}。
     * 阶段1 保留 SPOT 兼容,6 调用点迁移到新签名留阶段2(§11 B2-new/B6)。
     */
    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   leverage, margin_mode, position_side, liquidation_price, maint_margin, frozen_amount,
                   version, created_at, updated_at
            FROM positions
            WHERE account_id = #{accountId} AND symbol = #{symbol}
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "avg_entry_price", property = "avgEntryPrice"),
        @Result(column = "realized_pnl", property = "realizedPnl"),
        @Result(column = "margin_mode", property = "marginMode", typeHandler = EnumTypeHandler.class),
        @Result(column = "position_side", property = "positionSide"),
        @Result(column = "liquidation_price", property = "liquidationPrice"),
        @Result(column = "maint_margin", property = "maintMargin"),
        @Result(column = "frozen_amount", property = "frozenAmount"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    Position findByAccountAndSymbol(@Param("accountId") long accountId, @Param("symbol") String symbol);

    /**
     * PERP 合约持仓查询(§11 B2-new):按 position_side + margin_mode 定位唯一行(对齐 V31 唯一索引
     * (account_id, symbol, COALESCE(position_side,'LONG'), COALESCE(margin_mode,'SPOT')))。
     * market_type 列不存在,marketType 从 marginMode 派生(§13 M8-impl)。
     * SPOT 持仓 positionSide/marginMode 传 null,COALESCE 默认 'LONG'/'SPOT' 命中。
     */
    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   leverage, margin_mode, position_side, liquidation_price, maint_margin, frozen_amount,
                   version, created_at, updated_at
            FROM positions
            WHERE account_id = #{accountId} AND symbol = #{symbol}
              AND COALESCE(position_side, 'LONG') = COALESCE(#{positionSide}, 'LONG')
              AND COALESCE(margin_mode, 'SPOT') = COALESCE(#{marginMode}, 'SPOT')
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "avg_entry_price", property = "avgEntryPrice"),
        @Result(column = "realized_pnl", property = "realizedPnl"),
        @Result(column = "margin_mode", property = "marginMode", typeHandler = EnumTypeHandler.class),
        @Result(column = "position_side", property = "positionSide"),
        @Result(column = "liquidation_price", property = "liquidationPrice"),
        @Result(column = "maint_margin", property = "maintMargin"),
        @Result(column = "frozen_amount", property = "frozenAmount"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    Position findByAccountSymbolPosition(
            @Param("accountId") long accountId,
            @Param("symbol") String symbol,
            @Param("positionSide") String positionSide,
            @Param("marginMode") MarginMode marginMode);

    @Update(
            """
            UPDATE positions
            SET side = #{side},
                qty = #{qty},
                avg_entry_price = #{avgEntryPrice},
                realized_pnl = #{realizedPnl},
                leverage = #{leverage},
                margin_mode = #{marginMode},
                position_side = #{positionSide},
                liquidation_price = #{liquidationPrice},
                maint_margin = #{maintMargin},
                frozen_amount = #{frozenAmount},
                version = version + 1,
                updated_at = now()
            WHERE id = #{id} AND version = #{version}
            """)
    int casUpdate(Position position);

    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   leverage, margin_mode, position_side, liquidation_price, maint_margin, frozen_amount,
                   version, created_at, updated_at
            FROM positions
            WHERE account_id = #{accountId}
            ORDER BY symbol ASC
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "avg_entry_price", property = "avgEntryPrice"),
        @Result(column = "realized_pnl", property = "realizedPnl"),
        @Result(column = "margin_mode", property = "marginMode", typeHandler = EnumTypeHandler.class),
        @Result(column = "position_side", property = "positionSide"),
        @Result(column = "liquidation_price", property = "liquidationPrice"),
        @Result(column = "maint_margin", property = "maintMargin"),
        @Result(column = "frozen_amount", property = "frozenAmount"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<Position> findByAccount(@Param("accountId") long accountId);

    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   leverage, margin_mode, position_side, liquidation_price, maint_margin, frozen_amount,
                   version, created_at, updated_at
            FROM positions
            WHERE id = #{id}
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "avg_entry_price", property = "avgEntryPrice"),
        @Result(column = "realized_pnl", property = "realizedPnl"),
        @Result(column = "margin_mode", property = "marginMode", typeHandler = EnumTypeHandler.class),
        @Result(column = "position_side", property = "positionSide"),
        @Result(column = "liquidation_price", property = "liquidationPrice"),
        @Result(column = "maint_margin", property = "maintMargin"),
        @Result(column = "frozen_amount", property = "frozenAmount"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    Position findById(@Param("id") long id);

    /** 删除某账户所有持仓(重置模拟盘用,清空持仓表)。 */
    @Delete("DELETE FROM positions WHERE account_id = #{accountId}")
    int deleteByAccount(@Param("accountId") long accountId);
}
