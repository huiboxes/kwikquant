package com.kwikquant.shared.types;

import java.util.List;

/**
 * 通用分页响应 DTO。
 *
 * @param content  当前页数据
 * @param page     当前页码
 * @param pageSize 每页大小
 * @param total    总记录数
 * @param totalPages 总页数
 */
public record PageDto<T>(List<T> content, int page, int pageSize, long total, int totalPages) {

    public static <T> PageDto<T> of(List<T> content, int page, int pageSize, long total) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        return new PageDto<>(content, page, pageSize, total, totalPages);
    }
}
