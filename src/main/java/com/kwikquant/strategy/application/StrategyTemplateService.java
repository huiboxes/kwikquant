package com.kwikquant.strategy.application;

import com.kwikquant.shared.types.Interval;
import com.kwikquant.strategy.domain.BacktestQuotaExceededException;
import com.kwikquant.strategy.domain.BacktestTask;
import com.kwikquant.strategy.domain.BacktestWorkerUnavailableException;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyTemplate;
import com.kwikquant.strategy.domain.TemplateNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 策略模板服务：目录查询 + fork 编排。
 *
 * <p>fork 编排的事务结构（与 {@link BacktestTaskService#submit} 不加事务同理）：
 * <ol>
 *   <li>{@link TemplateForkCreator#createForked} 独立事务落库策略 + 已发布代码 + 标记就绪，返回即已提交；
 *   <li>首回测 submit 在事务外 best-effort 调用——若在事务内，{@code @Async} 执行线程读不到
 *       未提交的任务；且配额/worker 失败只应降级（skipReason）而不回滚 fork。
 * </ol>
 */
@Service
public class StrategyTemplateService {

    private static final Logger log = LoggerFactory.getLogger(StrategyTemplateService.class);

    private final StrategyTemplateCatalog catalog;
    private final TemplateForkCreator forkCreator;
    private final BacktestTaskService backtestTaskService;

    public StrategyTemplateService(
            StrategyTemplateCatalog catalog, TemplateForkCreator forkCreator, BacktestTaskService backtestTaskService) {
        this.catalog = catalog;
        this.forkCreator = forkCreator;
        this.backtestTaskService = backtestTaskService;
    }

    public List<StrategyTemplate> list() {
        return catalog.all();
    }

    /** 按 key 取模板，不存在抛 {@link TemplateNotFoundException}（404/7008）。 */
    public StrategyTemplate require(String key) {
        StrategyTemplate template = catalog.get(key);
        if (template == null) {
            throw new TemplateNotFoundException(key);
        }
        return template;
    }

    /** fork 模板为当前用户策略，并 best-effort 提交首次回测（失败降级为 skipReason，不回滚 fork）。 */
    public TemplateForkResult fork(String key, long userId) {
        StrategyTemplate template = require(key);
        StrategyDefinition strategy = forkCreator.createForked(userId, template);
        Long taskId = null;
        String skipReason = null;
        try {
            BacktestTask task = submitFirstBacktest(strategy.getId(), userId, template);
            taskId = task.getId();
        } catch (BacktestQuotaExceededException e) {
            skipReason = "回测并发配额已满，请稍后在策略工作台手动提交首次回测";
        } catch (BacktestWorkerUnavailableException e) {
            // 自检/搭建过渡窗口与真实故障分开表述：前者稍后重试即可，不该让用户以为平台故障
            String reason = e.getMessage();
            boolean transitional = reason != null
                    && (reason.contains(BacktestWorkerHealthChecker.AUTO_SETUP_MARKER)
                            || reason.contains(BacktestWorkerHealthChecker.SELF_CHECK_MARKER));
            skipReason = transitional ? "回测运行环境正在自动准备（首次约 1-3 分钟），稍后可在策略工作台提交首次回测" : "回测服务暂不可用，请稍后在策略工作台手动提交首次回测";
        } catch (RuntimeException e) {
            log.warn("fork template {} auto first backtest failed (strategy {})", key, strategy.getId(), e);
            skipReason = "自动回测提交失败，请在策略工作台手动提交首次回测";
        }
        return new TemplateForkResult(strategy, taskId, skipReason);
    }

    /**
     * 模板推荐窗口的首回测：{@code [now - backtestWindowDays, now)}，endTime 对齐 interval 网格
     * （整网格区间利于 DB 快照完整覆盖判定，减少交易所 API 零碎补拉）。days 取值保证 bar 数不超
     * max-bars（catalog 测试守护）。symbol/exchange/interval 回落策略默认值（= 模板声明值）。
     */
    private BacktestTask submitFirstBacktest(long strategyId, long userId, StrategyTemplate template) {
        long intervalMs = Interval.fromCcxt(template.intervalValue()).toMillis();
        long alignedEndMs = Instant.now().toEpochMilli() / intervalMs * intervalMs;
        Instant endTime = Instant.ofEpochMilli(alignedEndMs);
        Instant startTime = endTime.minus(Duration.ofDays(template.backtestWindowDays()));
        return backtestTaskService.submit(
                strategyId, userId, null, null, null, startTime, endTime, template.parameters());
    }
}
