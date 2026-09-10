package com.kwikquant.trading.interfaces;

import com.kwikquant.trading.application.FillsSinceResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * GET /api/v1/worker/fills-since 响应体：增量成交行 + 游标推进值。
 *
 * @param fills id ASC，强平行已排除（强平成交走 LiquidationEvent 通道，补拉不得把它
 *        派发成 on_fill——通道互斥契约 docs/strategy-api.md §8）
 * @param cursor 推荐游标（播种模式=当前安全尾部 id；页非空=页内最后一行 id；空页=回显
 *        afterId）。worker 侧取 max(本地游标, cursor) 前进，配合重叠重拉 + fillId 去重
 */
public record FillsSinceView(
        @Schema(description = "增量成交行（id ASC，不含强平行）") List<FillCatchupDto> fills,
        @Schema(description = "推荐游标（下次请求的 afterId 基准）", example = "1024") long cursor) {

    public static FillsSinceView from(FillsSinceResult result) {
        return new FillsSinceView(
                result.fills().stream().map(FillCatchupDto::from).toList(), result.cursor());
    }
}
