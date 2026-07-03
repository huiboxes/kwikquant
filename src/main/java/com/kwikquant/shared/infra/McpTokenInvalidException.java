package com.kwikquant.shared.infra;

/**
 * PAT 无效（不存在/已吊销/已过期）。filter 层 401，映射 {@link ErrorCode#MCP_TOKEN_INVALID} (10001)。
 *
 * <p>verify 返 null 时由 filter 抛出；controller 层不抛（PAT issue 是 REST 端点，不走 verify）。
 */
public class McpTokenInvalidException extends RuntimeException {
    public McpTokenInvalidException(String message) {
        super(message);
    }
}
