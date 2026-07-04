package com.kwikquant.shared.infra;

/**
 * MCP 高危操作（{@code start_live_trading} / {@code emergency_stop}）缺 {@code confirm=true} 二次确认。
 * controller 层 400，映射 {@link ErrorCode#MCP_EMERGENCY_CONFIRM_REQUIRED} (10004)。
 *
 * <p>Step 5 {@code start_live_trading} + Step 7 {@code emergency_stop} 抛出。
 */
public class McpEmergencyConfirmRequiredException extends RuntimeException {
    public McpEmergencyConfirmRequiredException(String message) {
        super(message);
    }
}
