package com.kwikquant.ai.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 ai 模块的 {@link ConfigurationProperties} bean：{@link ContextWindowProperties}
 * （上下文窗口预算，被 {@link ContextWindowManager} 注入）与 {@link LlmProperties}
 * （各 provider 默认模型，被 LLM adapter 注入）。参照 {@code QuoteCurrencyConfiguration} 范式。
 */
@Configuration
@EnableConfigurationProperties({ContextWindowProperties.class, LlmProperties.class})
class AiConfiguration {}
