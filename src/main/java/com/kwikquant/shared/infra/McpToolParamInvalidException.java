package com.kwikquant.shared.infra;

/**
 * MCP 工具入参非法（exchange/ruleType 枚举值不合法、SPOT 调 funding_rate、PAPER 调实时行情等）。
 * controller 层 400，映射 {@link ErrorCode#MCP_TOOL_PARAM_INVALID} (10002)。
 *
 * <p>Step 3+ @McpTool 方法抛出；Step 1 仅创建类，filter/controller 不抛。
 */
public class McpToolParamInvalidException extends RuntimeException {
    public McpToolParamInvalidException(String message) {
        super(message);
    }
}
