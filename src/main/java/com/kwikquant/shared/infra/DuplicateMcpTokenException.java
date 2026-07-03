package com.kwikquant.shared.infra;

/**
 * 同用户同名 PAT 重复。controller 层 400，复用 {@link ErrorCode#VALIDATION_FAILED} (3001)。
 *
 * <p>PAT issue 是 REST 端点非 @McpTool，故复用 3001 而非 10002 工具入参码（§3.1 异常表）。
 */
public class DuplicateMcpTokenException extends RuntimeException {
    public DuplicateMcpTokenException(String name) {
        super("mcp token name already exists for this user: " + name);
    }
}
