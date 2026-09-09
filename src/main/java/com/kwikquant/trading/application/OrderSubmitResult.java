package com.kwikquant.trading.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.kwikquant.shared.types.OrderStatus;
import com.kwikquant.trading.domain.Order;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 下单返回结果。
 *
 * <p>{@code filledQty}/{@code filledAvgPrice} 是**提交时点**的成交信息，不是成交承诺：成交异步推进
 * （PAPER 由行情推送驱动撮合，提交返回时市价单通常仍为 0；LIVE 由交易所异步回报，提交时为 null）。
 * 幂等 replay 命中已成交订单时返回真实累计值。成交真相以 /topic/fills 推送与订单/持仓查询为准——
 * 客户端不得以本结果的 filledQty 判定"已成交/未成交需要重下"（重下判断误用会造成重复下单）。
 *
 * <p>金额字段序列化为字符串（金额红线：消费方直接十进制解析，不经 JSON number/float 中转）。
 */
public record OrderSubmitResult(
        long orderId,
        OrderStatus status,
        long version,
        Instant createdAt,
        @Schema(
                        type = "string",
                        nullable = true,
                        description = "提交时点已成交数量（decimal string，金额红线不经 JSON number；成交异步推进："
                                + "PAPER 提交时通常为 \"0\"，LIVE 为 null；幂等 replay 返回订单当前真实累计值）",
                        example = "0")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal filledQty,
        @Schema(
                        type = "string",
                        nullable = true,
                        description = "提交时点成交均价（decimal string，与 filledQty 同一时点口径；尚无成交为 null）",
                        example = "42150.50")
                @JsonFormat(shape = JsonFormat.Shape.STRING)
                BigDecimal filledAvgPrice) {

    public static OrderSubmitResult from(Order order) {
        return new OrderSubmitResult(
                order.getId(),
                order.getStatus(),
                order.getVersion(),
                order.getCreatedAt(),
                order.getFilledQty(),
                order.getFilledAvgPrice());
    }
}
