package com.kwikquant.trading.domain;

/**
 * 拒绝成交异常:PERP CLOSE_* 平仓时本次 fillQty 超过持仓 qty 抛出。
 *
 * <p>B2-s ③ {@code applyPerpDelta} 处理 CLOSE_LONG/CLOSE_SHORT 分支时,若本次成交数量
 * 超过当前持仓数量,优先抛异常而非 cap 到持仓量(§13 拍板),与 LIVE 执行链路保持一致
 * (LIVE 链路同样优先抛以暴露上游算错)。SPOT 链路沿用既有 cap 容错逻辑不变,
 * 本异常仅 PERP 平仓分支使用。
 */
public class RejectFillException extends MatchingException {

    public RejectFillException(String message) {
        super(message);
    }

    public RejectFillException(String message, Throwable cause) {
        super(message, cause);
    }
}
