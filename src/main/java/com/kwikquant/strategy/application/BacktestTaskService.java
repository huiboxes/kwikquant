package com.kwikquant.strategy.application;

import com.kwikquant.shared.infra.OwnershipCheck;
import com.kwikquant.shared.types.Exchange;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestTaskNotFoundException;
import com.kwikquant.strategy.domain.NoPublishedStrategyCodeException;
import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.infrastructure.BacktestTaskMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 回测任务服务。提交 PENDING 任务 → 触发异步执行；状态/结果查询。
 *
 * <p><b>submit 不加 {@code @Transactional}（架构师决策）</b>：submit 仅一次 insert，无跨写一致性需求。
 * 显式事务反而引发 {@code @Async} 读未提交问题——{@code executionGateway.executeAsync} 在新线程跑，若 submit
 * 持有事务未提交，异步线程 {@code findById} 读不到任务→skip。去掉事务让 insert 立即 auto-commit，异步线程可见。
 *
 * <p><b>Wave 6 范围</b>：只建任务框架（提交/状态/结果/WebSocket），实际执行走
 * {@link BacktestExecutionGateway} → Wave 8 Python Worker(回测不在此模块)。
 */
@Service
public class BacktestTaskService {

    private final BacktestTaskMapper taskMapper;
    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final BacktestExecutionGateway executionGateway;

    public BacktestTaskService(
            BacktestTaskMapper taskMapper,
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            BacktestExecutionGateway executionGateway) {
        this.taskMapper = taskMapper;
        this.crudService = crudService;
        this.codeService = codeService;
        this.executionGateway = executionGateway;
    }

    public BacktestTask submit(
            long strategyId,
            long userId,
            String symbol,
            String exchange,
            String intervalValue,
            Instant startTime,
            Instant endTime,
            String parameters) {
        StrategyDefinition strategy = crudService.getOwned(strategyId, userId);
        StrategyCode code = codeService.getPublishedCode(strategyId);
        if (code == null) {
            throw new NoPublishedStrategyCodeException(strategyId);
        }
        String resolvedSymbol = symbol != null ? symbol : strategy.getSymbol();
        String resolvedExchange = exchange != null ? exchange : strategy.getExchange();
        String resolvedInterval = intervalValue != null ? intervalValue : strategy.getIntervalValue();
        // 轻量校验:exchange 必须是真实枚举(非 PAPER,模拟盘 exchange='OKX' 非 PAPER)、startTime<endTime、
        // symbol 非空。非法抛 IllegalArgumentException(@RestControllerAdvice 转 3001 VALIDATION_FAILED / 400)。
        validateBacktestParams(resolvedSymbol, resolvedExchange, startTime, endTime);
        BacktestTask task = BacktestTask.create(
                strategyId,
                userId,
                code.getId(),
                resolvedSymbol,
                resolvedExchange,
                resolvedInterval,
                startTime,
                endTime,
                parameters);
        taskMapper.insert(task);
        executionGateway.executeAsync(task.getId());
        return task;
    }

    private static void validateBacktestParams(String symbol, String exchange, Instant startTime, Instant endTime) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("backtest symbol must not be blank");
        }
        if (exchange == null || exchange.isBlank()) {
            throw new IllegalArgumentException("backtest exchange must not be blank");
        }
        Exchange ex;
        try {
            ex = Exchange.valueOf(exchange);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("backtest exchange invalid: " + exchange);
        }
        if (ex == Exchange.PAPER) {
            throw new IllegalArgumentException(
                    "backtest exchange must not be PAPER (use real exchange like OKX/BINANCE)");
        }
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("backtest startTime must be before endTime");
        }
    }

    public BacktestTask getOwned(long taskId, long userId) {
        BacktestTask task = taskMapper.findById(taskId);
        if (task == null) {
            throw new BacktestTaskNotFoundException(taskId);
        }
        return OwnershipCheck.requireOwned(task, task.getUserId(), userId, "backtest_task");
    }

    public List<BacktestTask> listByStrategy(long strategyId, long userId) {
        crudService.getOwned(strategyId, userId);
        return taskMapper.findByStrategyId(strategyId);
    }
}
