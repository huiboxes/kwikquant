package com.kwikquant.trading.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * GET /api/v1/orders 分页查询参数。
 *
 * @param accountId 必填
 * @param symbol    可选过滤
 * @param status    可选多状态过滤（逗号分隔）
 * @param startTime 可选 created_at 下限（ISO-8601）
 * @param endTime   可选 created_at 上限（ISO-8601）
 * @param page      页码（默认 1）
 * @param pageSize  每页大小（默认 50，最大 200）
 */
public record OrderListQuery(
        @Schema(description = "账户 ID，必填，鉴权校验归属", example = "7", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
                Long accountId,
        @Schema(description = "按 canonical symbol 过滤", example = "BTC/USDT") String symbol,
        @Schema(
                        description = "按状态过滤，多值逗号分隔（枚举: NEW | PARTIAL | FILLED | CANCELLED | REJECTED | EXPIRED）",
                        example = "FILLED")
                String status,
        @Schema(description = "created_at 下限 ISO-8601", example = "2026-07-01T00:00:00Z") String startTime,
        @Schema(description = "created_at 上限 ISO-8601", example = "2026-07-04T00:00:00Z") String endTime,
        @Schema(description = "页码，1-based，默认 1", example = "1") @Min(1) Integer page,
        @Schema(description = "每页条数，默认 50，最大 200", example = "50") @Min(1) @Max(200) Integer pageSize) {

    public int effectivePage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int effectivePageSize() {
        if (pageSize == null || pageSize < 1) return 50;
        return Math.min(pageSize, 200);
    }
}
