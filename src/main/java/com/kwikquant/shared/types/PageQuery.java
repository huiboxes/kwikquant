package com.kwikquant.shared.types;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "分页查询参数，封装 page/pageSize 校验与 offset 计算")
public record PageQuery(
        @Schema(description = "页码，1-based", example = "1") int page,
        @Schema(description = "每页大小", example = "20") int pageSize) {

    private static final int STANDARD_DEFAULT = 20;
    private static final int STANDARD_MAX = 100;
    private static final int LARGE_DEFAULT = 50;
    private static final int LARGE_MAX = 200;

    public PageQuery {
        if (page < 1) throw new IllegalArgumentException("page must be >= 1, got " + page);
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be >= 1, got " + pageSize);
    }

    public static PageQuery of(Integer page, Integer pageSize, int defaultPageSize, int maxPageSize) {
        int p = (page == null || page < 1) ? 1 : page;
        int ps = (pageSize == null || pageSize < 1) ? defaultPageSize : Math.min(pageSize, maxPageSize);
        return new PageQuery(p, ps);
    }

    /** 标准分页：默认 20 条/页，上限 100。适用于报告、交易历史等常规列表。 */
    public static PageQuery ofStandard(Integer page, Integer pageSize) {
        return of(page, pageSize, STANDARD_DEFAULT, STANDARD_MAX);
    }

    /** 大分页：默认 50 条/页，上限 200。适用于订单、风控决策等高频列表。 */
    public static PageQuery ofLarge(Integer page, Integer pageSize) {
        return of(page, pageSize, LARGE_DEFAULT, LARGE_MAX);
    }

    public int offset() {
        return (page - 1) * pageSize;
    }
}
