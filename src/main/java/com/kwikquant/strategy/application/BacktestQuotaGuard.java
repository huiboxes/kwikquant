package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestQuotaExceededException;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回测配额守卫:同事务内 lock → count → insert,消除 count-then-insert 并发竞态。
 *
 * <p><b>竞态</b>:旧实现在 {@code BacktestTaskService.submit} 里先 {@code countActiveByUser} 再 insert,
 * 两步之间无互斥——同一用户并发 N 个提交可同时读到 count=0 → 全部 insert,突破
 * {@code max-concurrent-per-user}(write skew)。
 *
 * <p><b>方案</b>:独立 Bean + {@code @Transactional}(避开同类自调用代理失效),事务内先取
 * per-user {@code pg_advisory_xact_lock}(锁键 = userId,随事务提交/回滚自动释放),再 count → 超限抛
 * {@link BacktestQuotaExceededException} 回滚、否则 insert。同用户并发提交被串行化:后到者阻塞至先到者
 * 事务提交后才 count,看到新插入的任务 → 配额超限被拒。单节点部署下完备(多实例需外置配额,见部署红线)。
 *
 * <p><b>事务边界与 @Async 可见性</b>:本方法事务在返回前提交,调用方 {@link BacktestTaskService#submit}
 * 后续 {@code executionGateway.executeAsync(taskId)} 已可见已插入的行(与 submit 不加事务的原考虑一致)。
 */
@Component
public class BacktestQuotaGuard {

    private final BacktestTaskMapper taskMapper;
    private final int maxConcurrentPerUser;

    public BacktestQuotaGuard(
            BacktestTaskMapper taskMapper,
            @Value("${kwikquant.backtest.max-concurrent-per-user:2}") int maxConcurrentPerUser) {
        this.taskMapper = taskMapper;
        this.maxConcurrentPerUser = maxConcurrentPerUser;
    }

    /**
     * 配额内插入回测任务(事务:advisory lock + count + insert)。
     *
     * @param task PENDING 任务(userId 用于配额计数与锁键)
     * @return 已插入的任务(id 由 {@code useGeneratedKeys} 回填)
     * @throws BacktestQuotaExceededException per-user PENDING+RUNNING ≥ max 时抛出(HTTP 429 / 7306)
     */
    @Transactional
    public BacktestTask insertWithinQuota(BacktestTask task) {
        taskMapper.lockBacktestQuota(task.getUserId());
        int active = taskMapper.countActiveByUser(task.getUserId());
        if (active >= maxConcurrentPerUser) {
            throw new BacktestQuotaExceededException(active, maxConcurrentPerUser);
        }
        taskMapper.insert(task);
        return task;
    }
}
