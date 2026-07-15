package com.kwikquant.report.interfaces;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ActivityFeedItemDto(
        @Schema(description = "事件类型", example = "ORDER_FILLED") String type,
        @Schema(description = "事件标题", example = "BTC/USDT BUY 0.42 @ 61200") String title,
        @Schema(description = "事件副标题", example = "PAPER · 全部成交") String subtitle,
        @Schema(description = "事件时间", example = "2026-07-14T14:02:00Z") Instant timestamp) {}
