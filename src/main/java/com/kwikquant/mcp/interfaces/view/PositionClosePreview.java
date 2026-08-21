package com.kwikquant.mcp.interfaces.view;

/** close_position 两阶段确认预览:待平持仓要素回显(positionSide 仅 PERP 有值)。 */
public record PositionClosePreview(
        Long positionId, Long accountId, String symbol, String side, String qty, String positionSide) {}
