package com.kwikquant.strategy.application;

import com.kwikquant.strategy.domain.StrategyCode;
import com.kwikquant.strategy.domain.StrategyDefinition;
import com.kwikquant.strategy.domain.StrategyTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模板 fork 落库：建用户策略 + 注入模板源码并发布。create / createDraft / publish 三步
 * 在同一事务内原子完成——要么 fork 出"带已发布代码的策略"，要么整体回滚，不留无码空策略。
 *
 * <p>独立成 Bean 而非内嵌 {@link StrategyTemplateService}：fork 编排（落库 + 事务外提交首回测）
 * 与落库事务必须分开——首回测 submit 若在 fork 事务内，{@code @Async} 执行线程会读不到未提交
 * 的任务（与 {@code BacktestTaskService.submit} 不加事务同理）。自调用不过 AOP 代理，故拆类。
 */
@Service
public class TemplateForkCreator {

    private final StrategyCrudService crudService;
    private final StrategyCodeService codeService;

    public TemplateForkCreator(StrategyCrudService crudService, StrategyCodeService codeService) {
        this.crudService = crudService;
        this.codeService = codeService;
    }

    /** fork 模板为用户策略：DRAFT 策略 + 模板源码直接发布（fork 产物即可回测/就绪）。 */
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
        return strategy;
    }
}
