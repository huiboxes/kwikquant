package com.kwikquant.mcp.interfaces.view;

import java.util.List;

/**
 * MCP {@code emergency_stop} 工具返回视图。
 * <ul>
 *   <li>{@code batchUuid}：本次紧急停止的批次 UUID（前置审计 {@code AuditEntry.targetId=batchUuid}，
 *       各单条 stop 走 {@code StrategyLifecycleService.stop} 的 @Auditable(targetId=strategyId)）
 *   <li>{@code stoppedCount}：实际成功停止的策略数（部分失败不中断，返实际数）
 *   <li>{@code strategyIds}：成功停止的策略 ID 列表
 *   <li>{@code failedStrategyIds}：停止失败的策略 ID 列表（kill switch 运维盲区修复：部分失败时
 *       operator 须知道哪些策略仍 RUNNING，与前置 EMERGENCY_STOP 审计的 fail-closed 严肃性一致）
 * </ul>
 * 无 RUNNING 策略时返 {@code stoppedCount:0}（非错误）。
 */
public record EmergencyStopView(
        String batchUuid, int stoppedCount, List<Long> strategyIds, List<Long> failedStrategyIds) {}
