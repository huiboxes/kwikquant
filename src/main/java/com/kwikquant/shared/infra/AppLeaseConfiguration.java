package com.kwikquant.shared.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 {@link AppLeaseProperties} bean(仿 {@link ProxyConfiguration})。 */
@Configuration
@EnableConfigurationProperties(AppLeaseProperties.class)
class AppLeaseConfiguration {}
