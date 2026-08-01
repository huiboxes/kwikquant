package com.kwikquant.strategy.domain;

import com.kwikquant.shared.infra.ResourceNotFoundException;

/**
 * 策略代码版本不存在。两种 not-found 语义，消息 label 区分避免把 strategyId 误当 codeId 显示(M1):
 * <ol>
 *   <li>按 codeId 查找不存在(requireOwnedCode 路径):"StrategyCode not found: &lt;codeId&gt;"</li>
 *   <li>按 strategyId 查某版本不存在(getDraftCodeOwned/getPublishedCodeOwned):
 *       "&lt;version&gt; code for strategy not found: &lt;strategyId&gt;"
 *       —— 此前两处传 strategyId 进单参构造器,label "StrategyCode" 配 strategyId 值,
 *          用户看到 "StrategyCode not found: 5" 会误把 strategyId 当 codeId 去查。</li>
 * </ol>
 *
 * <p>映射 {@code STRATEGY_CODE_NOT_FOUND}(7004) + 404,由 {@code StrategyExceptionHandler} 处理。
 */
public class StrategyCodeNotFoundException extends ResourceNotFoundException {

    /** 按 codeId 查找不存在(requireOwnedCode 路径)。 */
    public StrategyCodeNotFoundException(long codeId) {
        super("StrategyCode", codeId);
    }

    /**
     * 按 strategyId 查某版本(DRAFT/PUBLISHED)不存在。
     *
     * @param strategyId 策略 ID(消息里显示此值,而非 codeId)
     * @param version 版本标识,如 "DRAFT"/"PUBLISHED"(拼进消息 label 区分来源)
     */
    public StrategyCodeNotFoundException(long strategyId, String version) {
        super(version + " code for strategy", strategyId);
    }
}
