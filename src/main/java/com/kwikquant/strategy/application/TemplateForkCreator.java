package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模板 fork 落库：建用户策略 + 注入模板源码并发布 + 标记就绪。create / createDraft /
 * publish / ready 四步在同一事务内原子完成——要么 fork 出"带已发布代码且可启动的策略"，
 * 要么整体回滚，不留无码空策略。
 *
 * <p>独立成 Bean 而非内嵌 {@link StrategyTemplateService}：fork 编排（落库 + 事务外提交首回测）
 * 与落库事务必须分开——首回测 submit 若在 fork 事务内，{@code @Async} 执行线程会读不到未提交
 * 的任务（与 {@code BacktestTaskService.submit} 不加事务同理）。自调用不过 AOP 代理，故拆类。
 */
@Service
public class TemplateForkCreator {

    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;
    private final StrategyLifecycleService lifecycleService;

    public TemplateForkCreator(
            StrategyCrudService crudService,
            StrategyCodeService codeService,
            StrategyLifecycleService lifecycleService) {
        this.crudService = crudService;
        this.codeService = codeService;
        this.lifecycleService = lifecycleService;
    }

    /** fork 模板为用户策略：源码直接发布并标记 READY（模板定位"拿来即用"，出生即可回测/启动）。 */
    @Transactional
    public StrategyDefinition createForked(long userId, StrategyTemplate template) {
        StrategyDefinition strategy = crudService.create(
                userId,
                template.name(),
                template.description(),
                template.symbol(),
                template.exchange(),
                "SPOT",
                null,
                null,
                template.intervalValue(),
                template.parameters());
        StrategyCode draft = codeService.createDraft(
                strategy.getId(), userId, template.sourceCode(), "fork 自官方模板 " + template.key());
        codeService.publish(strategy.getId(), userId, draft.getId());
        return lifecycleService.ready(strategy.getId(), userId);
    }
}
