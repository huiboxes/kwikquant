package com.kwikquant.trading.application;

/**
 * trading::application 内跨类共享的数值常量，避免同一业务参数在多个 CAS 重试循环里各自重复定义
 * 导致未来调整时遗漏。
 */
final class TradingConstants {

    /** CAS（乐观锁版本号）冲突重试次数上限，PositionService/ExecutionService/LiveExecutor/PaperExecutor 共用。 */
    static final int MAX_CAS_RETRIES = 3;

    private TradingConstants() {}
}
