package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.BacktestTaskStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 全列表回测摘要(application 层 view record)。
 *
 * <p>供 {@link com.kwikquant.strategy.interfaces.BacktestController.BacktestTaskDto#from(BacktestTaskSummary)}
 * 转 DTO。含 {@code totalReturn} + {@code strategyName}({@link com.kwikquant.strategy.domain.BacktestTask}
 * domain 无此二字段,service 层组装:totalReturn 走
 * {@link com.kwikquant.report.application.ReportService#findTotalReturnsByIds} 批量取,strategyName 走
 * {@link StrategyCrudService#listByUser(long)} 批量取)。
 *
 * <p><b>放 application 层(非 interfaces)</b>:避免 service 返 DTO 违反分层(application 不依赖 interfaces,
 * 正常方向是 interfaces 依赖 application)。
 */
public record BacktestTaskSummary(
        Long id,
        long strategyId,
        long strategyCodeId,
        BacktestTaskStatus status,
        String symbol,
        String exchange,
        String intervalValue,
        Instant startTime,
        Instant endTime,
        String parameters,
        String result,
        Long reportId,
        String errorMessage,
        Integer processedBars,
        Integer totalBars,
        Instant createdAt,
        Instant updatedAt,
        BigDecimal totalReturn,
        String strategyName) {}
