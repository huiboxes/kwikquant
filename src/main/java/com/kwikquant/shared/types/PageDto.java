package com.kwikquant.shared.types;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 通用分页响应 DTO。offset 分页（非游标）：{@code page} 1-based，{@code pageSize} 默认 20，{@code total} 为总条数。
 */
public record PageDto<T>(
        @Schema(description = "当前页数据") List<T> content,
        @Schema(description = "当前页码，1-based", example = "1") int page,
        @Schema(description = "每页大小，默认 20", example = "20") int pageSize,
        @Schema(description = "总记录数", example = "137") long total,
        @Schema(description = "总页数", example = "7") int totalPages) {

    public static <T> PageDto<T> of(List<T> content, int page, int pageSize, long total) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        return new PageDto<>(content, page, pageSize, total, totalPages);
    }
}
