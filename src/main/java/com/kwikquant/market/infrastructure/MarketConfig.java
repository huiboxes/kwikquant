package com.kwikquant.market.infrastructure;

import com.kwikquant.shared.types.Interval;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MarketProperties.class)
class MarketConfig {

    /** 让 @RequestParam Interval 接受 CCXT timeframe 字符串（如 "1h"）而非枚举常量名 "_1h"。 */
    @Bean
    Converter<String, Interval> intervalConverter() {
        return Interval::fromCcxt;
    }
}
