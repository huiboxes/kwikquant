package com.kwikquant.trading.interfaces;

/**
 * @deprecated Use {@link com.kwikquant.shared.types.PageDto} instead. Kept for backward compatibility.
 */
@Deprecated
public record PageDto<T>(java.util.List<T> content, int page, int pageSize, long total, int totalPages) {

    public static <T> PageDto<T> of(java.util.List<T> content, int page, int pageSize, long total) {
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        return new PageDto<>(content, page, pageSize, total, totalPages);
    }
}
