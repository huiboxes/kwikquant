package com.kwikquant.trading.infrastructure;

import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
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
public interface OrderMapper {

    @Insert(
            """
            INSERT INTO orders (account_id, client_order_id, symbol, side, order_type, amount,
                                price, stop_price, time_in_force, expire_at, status,
                                filled_qty, filled_avg_price, version)
            VALUES (#{accountId}, #{clientOrderId}, #{symbol}, #{side}, #{orderType}, #{amount},
                    #{price}, #{stopPrice}, #{timeInForce}, #{expireAt}, #{status},
                    #{filledQty}, #{filledAvgPrice}, #{version})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Order order);

    @Select(
            """
            SELECT id, account_id, client_order_id, exchange_order_id, symbol, side, order_type, amount,
                   price, stop_price, time_in_force, expire_at, status, filled_qty, filled_avg_price,
                   version, created_at, updated_at
            FROM orders WHERE id = #{id}
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "client_order_id", property = "clientOrderId"),
        @Result(column = "exchange_order_id", property = "exchangeOrderId"),
        @Result(column = "order_type", property = "orderType"),
        @Result(column = "stop_price", property = "stopPrice"),
        @Result(column = "time_in_force", property = "timeInForce"),
        @Result(column = "expire_at", property = "expireAt"),
        @Result(column = "filled_qty", property = "filledQty"),
        @Result(column = "filled_avg_price", property = "filledAvgPrice"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    Order findById(@Param("id") long id);

    /**
     * CAS update：仅当 version 匹配时才更新。返回影响行数（0=冲突，1=成功）。
     *
     * <p>更新字段包括 status / filled_qty / filled_avg_price / exchange_order_id。
     */
    @Update(
            """
            UPDATE orders
            SET status = #{status},
                filled_qty = #{filledQty},
                filled_avg_price = #{filledAvgPrice},
                exchange_order_id = #{exchangeOrderId},
                version = version + 1,
                updated_at = now()
            WHERE id = #{id} AND version = #{version}
            """)
    int casUpdate(Order order);

    @Select(
            """
            SELECT id, account_id, client_order_id, exchange_order_id, symbol, side, order_type, amount,
                   price, stop_price, time_in_force, expire_at, status, filled_qty, filled_avg_price,
                   version, created_at, updated_at
            FROM orders
            WHERE account_id = #{accountId}
              AND status NOT IN ('FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED')
            ORDER BY created_at ASC
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "client_order_id", property = "clientOrderId"),
        @Result(column = "exchange_order_id", property = "exchangeOrderId"),
        @Result(column = "order_type", property = "orderType"),
        @Result(column = "stop_price", property = "stopPrice"),
        @Result(column = "time_in_force", property = "timeInForce"),
        @Result(column = "expire_at", property = "expireAt"),
        @Result(column = "filled_qty", property = "filledQty"),
        @Result(column = "filled_avg_price", property = "filledAvgPrice"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<Order> findActiveByAccount(@Param("accountId") long accountId);

    /** GTD 扫描器用：找出已过期但未终态的订单。 */
    @Select(
            """
            SELECT id, account_id, client_order_id, exchange_order_id, symbol, side, order_type, amount,
                   price, stop_price, time_in_force, expire_at, status, filled_qty, filled_avg_price,
                   version, created_at, updated_at
            FROM orders
            WHERE time_in_force = 'GTD'
              AND status IN ('PENDING_NEW', 'SUBMITTED', 'PARTIALLY_FILLED')
              AND expire_at < #{cutoff}
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "client_order_id", property = "clientOrderId"),
        @Result(column = "exchange_order_id", property = "exchangeOrderId"),
        @Result(column = "order_type", property = "orderType"),
        @Result(column = "stop_price", property = "stopPrice"),
        @Result(column = "time_in_force", property = "timeInForce"),
        @Result(column = "expire_at", property = "expireAt"),
        @Result(column = "filled_qty", property = "filledQty"),
        @Result(column = "filled_avg_price", property = "filledAvgPrice"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<Order> findExpiredGtd(@Param("cutoff") java.time.Instant cutoff);

    /** 列表查询：按 status 列表 + 时间区间过滤 + 分页。 */
    @Select({
        "<script>",
        "SELECT id, account_id, client_order_id, exchange_order_id, symbol, side, order_type, amount,",
        "       price, stop_price, time_in_force, expire_at, status, filled_qty, filled_avg_price,",
        "       version, created_at, updated_at",
        "FROM orders",
        "WHERE account_id = #{accountId}",
        "<if test='symbol != null'>AND symbol = #{symbol}</if>",
        "<if test='statuses != null and !statuses.isEmpty()'>",
        "  AND status IN <foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>",
        "</if>",
        "<if test='startTime != null'>AND created_at &gt;= #{startTime}</if>",
        "<if test='endTime != null'>AND created_at &lt;= #{endTime}</if>",
        "ORDER BY created_at DESC",
        "LIMIT #{limit} OFFSET #{offset}",
        "</script>"
    })
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "client_order_id", property = "clientOrderId"),
        @Result(column = "exchange_order_id", property = "exchangeOrderId"),
        @Result(column = "order_type", property = "orderType"),
        @Result(column = "stop_price", property = "stopPrice"),
        @Result(column = "time_in_force", property = "timeInForce"),
        @Result(column = "expire_at", property = "expireAt"),
        @Result(column = "filled_qty", property = "filledQty"),
        @Result(column = "filled_avg_price", property = "filledAvgPrice"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    List<Order> findByQuery(
            @Param("accountId") long accountId,
            @Param("symbol") String symbol,
            @Param("statuses") List<OrderStatus> statuses,
            @Param("startTime") java.time.Instant startTime,
            @Param("endTime") java.time.Instant endTime,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select(
            """
            SELECT id, account_id, client_order_id, exchange_order_id, symbol, side, order_type, amount,
                   price, stop_price, time_in_force, expire_at, status, filled_qty, filled_avg_price,
                   version, created_at, updated_at
            FROM orders
            WHERE account_id = #{accountId} AND exchange_order_id = #{exchangeOrderId}
            """)
    @Results({
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "client_order_id", property = "clientOrderId"),
        @Result(column = "exchange_order_id", property = "exchangeOrderId"),
        @Result(column = "order_type", property = "orderType"),
        @Result(column = "stop_price", property = "stopPrice"),
        @Result(column = "time_in_force", property = "timeInForce"),
        @Result(column = "expire_at", property = "expireAt"),
        @Result(column = "filled_qty", property = "filledQty"),
        @Result(column = "filled_avg_price", property = "filledAvgPrice"),
        @Result(column = "created_at", property = "createdAt"),
        @Result(column = "updated_at", property = "updatedAt")
    })
    Order findByExchangeOrderId(
            @Param("accountId") long accountId, @Param("exchangeOrderId") String exchangeOrderId);

    /** 列表查询计数：与 findByQuery 相同的过滤条件。 */
    @Select({
        "<script>",
        "SELECT COUNT(*)",
        "FROM orders",
        "WHERE account_id = #{accountId}",
        "<if test='symbol != null'>AND symbol = #{symbol}</if>",
        "<if test='statuses != null and !statuses.isEmpty()'>",
        "  AND status IN <foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>",
        "</if>",
        "<if test='startTime != null'>AND created_at &gt;= #{startTime}</if>",
        "<if test='endTime != null'>AND created_at &lt;= #{endTime}</if>",
        "</script>"
    })
    long countByQuery(
            @Param("accountId") long accountId,
            @Param("symbol") String symbol,
            @Param("statuses") List<OrderStatus> statuses,
            @Param("startTime") java.time.Instant startTime,
            @Param("endTime") java.time.Instant endTime);
}
