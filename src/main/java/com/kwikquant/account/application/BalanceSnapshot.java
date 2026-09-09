package com.kwikquant.account.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 账户余额快照。金额字段序列化为 decimal string（金额红线：消费方直接十进制解析，
 * 不经 JSON number/float 中转——worker runner 权益计算与前端 money.ts 同源纪律）。
 */
public record BalanceSnapshot(
        @Schema(
                        description = "余额快照，key=币种代码，value=该币种余额明细",
                        example = "{\"USDT\":{\"free\":\"100000\",\"used\":\"0\",\"total\":\"100000\"}}")
                Map<String, CurrencyBalance> currencies) {

    public record CurrencyBalance(
            @Schema(
                            type = "string",
                            description = "可用余额（decimal string，精度 8 位）。PAPER 极端场景可为负："
                                    + "ISOLATED 穿蚀仓强平的负释放额由 free 吸收缺口（资金费侵蚀保证金穿仓，"
                                    + "保守口径由交易者承担，无保险基金）；ISOLATED 开仓实际保证金高于挂单冻结估算时同理",
                            example = "100000")
                    @JsonFormat(shape = JsonFormat.Shape.STRING)
                    BigDecimal free,
            @Schema(
                            type = "string",
                            description = "冻结余额（decimal string，精度 8 位）= 挂单冻结 + ISOLATED 锁定保证金；"
                                    + "资金费侵蚀可使 ISOLATED 仓位贡献为负（穿蚀仓）",
                            example = "0")
                    @JsonFormat(shape = JsonFormat.Shape.STRING)
                    BigDecimal used,
            @Schema(type = "string", description = "总余额（decimal string，精度 8 位）", example = "100000")
                    @JsonFormat(shape = JsonFormat.Shape.STRING)
                    BigDecimal total) {}
}
