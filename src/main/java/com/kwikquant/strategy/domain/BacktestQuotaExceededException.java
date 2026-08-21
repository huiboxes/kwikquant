package com.kwikquant.strategy.domain;

/**
 * 回测并发配额超限:用户已有 ≥ max-concurrent-per-user 个 PENDING/RUNNING 回测任务时再提交。
 * ErrorCode {@code 7306 BACKTEST_QUOTA_EXCEEDED},HTTP 429(StrategyExceptionHandler 映射)。
 *
 * <p>防单用户以长跑回测占满执行资源(每任务一个隔离容器 + 最长 timeout-sec 执行时间)。
 */
public class BacktestQuotaExceededException extends RuntimeException {

    private final int active;
    private final int max;

    public BacktestQuotaExceededException(int active, int max) {
        super("回测并发配额已满：当前 " + active + " 个进行中（上限 " + max + "），请等待现有回测结束后再提交");
        this.active = active;
        this.max = max;
    }

    public int active() {
        return active;
    }

    public int max() {
        return max;
    }
}
