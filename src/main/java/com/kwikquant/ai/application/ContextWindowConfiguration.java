package com.kwikquant.ai.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link ContextWindowProperties} bean（被 {@link ContextWindowManager} 注入）。
 * 参照 {@code QuoteCurrencyConfiguration} / {@code MarketConfig} 范式。
 */
@Configuration
@EnableConfigurationProperties(ContextWindowProperties.class)
class ContextWindowConfiguration {}
