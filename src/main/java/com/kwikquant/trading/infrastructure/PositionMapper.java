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

    /**
     * 查询某账户某 symbol 的所有 PERP 持仓(双向最多 LONG/SHORT 两行)。
     * <p>过滤条件 {@code margin_mode IN ('ISOLATED','CROSS')} 派生自 §13 M8-impl,
     * SPOT 持仓(margin_mode NULL)不返。供阶段2c 平仓链路 / 全量重算用。
     *
     * @return 该账户该 symbol 的 PERP 持仓列表(无则空 List)
     */
    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   leverage, margin_mode, position_side, liquidation_price, maint_margin, frozen_amount,
                   version, created_at, updated_at
            FROM positions
            WHERE account_id = #{accountId} AND symbol = #{symbol}
              AND margin_mode IN ('ISOLATED', 'CROSS')
            ORDER BY position_side ASC
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
    List<Position> findPerpBySymbol(@Param("accountId") long accountId, @Param("symbol") String symbol);

    /**
     * 查询所有 PERP 持仓(全账户)。供 {@code recomputeAllLiquidationPrices} 启动后全量重算用。
     * <p>过滤条件同 {@link #findPerpBySymbol},margin_mode IN ('ISOLATED','CROSS')。
     *
     * @return 全部 PERP 持仓列表
     */
    @Select(
            """
            SELECT id, account_id, symbol, side, qty, avg_entry_price, realized_pnl,
                   leverage, margin_mode, position_side, liquidation_price, maint_margin, frozen_amount,
                   version, created_at, updated_at
            FROM positions
            WHERE margin_mode IN ('ISOLATED', 'CROSS')
            ORDER BY account_id ASC, symbol ASC, position_side ASC
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
    List<Position> findAllPerpPositions();

    /**
     * 跨账户查某 symbol 的所有模拟盘 PERP 持仓(按交易所过滤)。阶段2f 强平判定用。
     *
     * <p>JOIN exchange_accounts 过滤 paper_trading=TRUE(只强平模拟盘,真实交易所强平由交易所侧处理)
     * + exchange=#{exchange}(按 ticker 来源交易所过滤,避免多交易所配置下串价强平,§11 m7-s)。
     *
     * <p><b>架构权衡</b>:V22 约定"下游表不引用 exchange_accounts"指 FK 约束,JOIN 查询不是 FK 引用
     * 故不违反;但 trading.infrastructure 知道 account 表结构是数据层耦合,未来可重构为
     * account 模块提供 findPaperAccountIdsByExchange(exchange) 服务 + positions IN clause 查询。
     *
     * @param symbol   交易对(BTC/USDT)
     * @param exchange ticker 来源交易所(只强平该交易所账户的持仓)
     * @return 该 symbol 该 exchange 的所有模拟盘 PERP 持仓(无则空 List)
     */
    @Select(
            """
            SELECT p.id, p.account_id, p.symbol, p.side, p.qty, p.avg_entry_price, p.realized_pnl,
                   p.leverage, p.margin_mode, p.position_side, p.liquidation_price, p.maint_margin, p.frozen_amount,
                   p.version, p.created_at, p.updated_at
            FROM positions p
            JOIN exchange_accounts a ON p.account_id = a.id
            WHERE p.symbol = #{symbol}
              AND p.margin_mode IN ('ISOLATED', 'CROSS')
              AND a.paper_trading = TRUE
              AND a.exchange = #{exchange}
            ORDER BY p.account_id ASC, p.position_side ASC
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
    List<Position> findAllPerpBySymbolAndExchange(
            @Param("symbol") String symbol, @Param("exchange") com.kwikquant.shared.types.Exchange exchange);

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
