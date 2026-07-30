package com.kwikquant.strategy.application;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 策略代码来源标识(混合方案:editor 模式前端传 sourceCode;DRAFT/PUBLISHED 后端按 strategyId 注入)。
 *
 * <p>{@code EDITOR} 走前端实时 code,避免未保存内容丢失;{@code DRAFT}/{@code PUBLISHED} 走后端
 * {@link StrategyCodeService},省 1MB body 且后端可信 audit。
 */
@Schema(description = "策略代码来源:editor 实时 / draft 草稿 / published 已发布")
public enum CodeSource {
    EDITOR,
    DRAFT,
    PUBLISHED
}
