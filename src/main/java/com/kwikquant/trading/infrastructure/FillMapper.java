package com.kwikquant.trading.infrastructure;

import com.kwikquant.trading.domain.Fill;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FillMapper {

    @Insert(
            """
            INSERT INTO fills (order_id, account_id, symbol, side, price, qty, fee, fee_currency,
                               liquidity, external_fill_id, filled_at)
            VALUES (#{orderId}, #{accountId}, #{symbol}, #{side}, #{price}, #{qty}, #{fee},
                    #{feeCurrency}, #{liquidity}, #{externalFillId}, #{filledAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Fill fill);

    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM fills
                          WHERE account_id = #{accountId}
                            AND external_fill_id = #{externalFillId})
            """)
    boolean existsByExternalFillId(
            @Param("accountId") long accountId, @Param("externalFillId") String externalFillId);

    @Select(
            """
            SELECT id, order_id, account_id, symbol, side, price, qty, fee, fee_currency,
                   liquidity, external_fill_id, filled_at
            FROM fills
            WHERE order_id = #{orderId}
            ORDER BY filled_at ASC
            """)
    @Results({
        @Result(column = "order_id", property = "orderId"),
        @Result(column = "account_id", property = "accountId"),
        @Result(column = "fee_currency", property = "feeCurrency"),
        @Result(column = "external_fill_id", property = "externalFillId"),
        @Result(column = "filled_at", property = "filledAt")
    })
    List<Fill> findByOrderId(@Param("orderId") long orderId);
}
