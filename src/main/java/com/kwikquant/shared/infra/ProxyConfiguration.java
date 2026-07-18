package com.kwikquant.shared.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link ProxyProperties} bean。独立于 market 模块的 MarketConfig,
 * 因 proxy 配置属 shared(被 account 的 BalanceService + market 的 CcxtExchangeRegistry 共用)。
 */
@Configuration
@EnableConfigurationProperties(ProxyProperties.class)
class ProxyConfiguration {}
