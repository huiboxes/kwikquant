package com.kwikquant.shared.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link CcxtProperties} bean。独立类(对齐 {@link ProxyConfiguration} 模式),
 * 让 account 的 {@code CcxtAuthExchangeFactory} + trading 的 {@code OkxRestClient} 共用同一 sandbox 信号。
 */
@Configuration
@EnableConfigurationProperties(CcxtProperties.class)
class CcxtConfiguration {}
