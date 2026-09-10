package com.kwikquant.trading.application;

import java.util.List;

/**
 * runner 断线增量补拉结果（{@code TradingService.listFillsSince} 返回）。
 *
 * @param fills 页内行（id ASC，强平行已排除——强平成交走 LiquidationEvent 通道不推
 *        FillEvent，补拉不得破坏通道互斥把它派发成 on_fill）；播种模式为空列表
 * @param cursor 推荐游标：播种模式（afterId 缺省）=当前安全尾部 id（无行则 0）；
 *        页非空=页内最后一行 id；空页=回显 afterId（游标不前进，worker 侧重叠重拉兜底）
 */
public record FillsSinceResult(List<FillCatchupRow> fills, long cursor) {}
