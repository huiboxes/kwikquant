package com.kwikquant.trading.interfaces;

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
        @NotNull Long accountId,
        String symbol,
        String status,
        String startTime,
        String endTime,
        @Min(1) Integer page,
        @Min(1) @Max(200) Integer pageSize) {

    public int effectivePage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int effectivePageSize() {
        if (pageSize == null || pageSize < 1) return 50;
        return Math.min(pageSize, 200);
    }
}
