package com.kwikquant.shared.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link QuoteCurrencyProperties} bean(shared,被 market 的 TradingPairService + account 的
 * BalanceService/ExchangeAccountService/PaperBalanceAdapter 共用)。参考 {@link ProxyConfiguration} 范式。
 */
@Configuration
@EnableConfigurationProperties(QuoteCurrencyProperties.class)
class QuoteCurrencyConfiguration {}
