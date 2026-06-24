package com.kwikquant.shared.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
class AuditConfig {

    @Bean
    AuditRepository auditRepository(DataSource dataSource, ObjectMapper objectMapper) {
        return new JdbcAuditRepository(dataSource, objectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    AuditExecutor auditExecutor(
            @Value("${kwikquant.audit.async-pool.core-pool-size:2}") int core,
            @Value("${kwikquant.audit.async-pool.max-pool-size:4}") int max,
            @Value("${kwikquant.audit.async-pool.queue-capacity:1000}") int queue,
            @Value("${kwikquant.audit.async-pool.keep-alive:60s}") java.time.Duration keepAlive) {
        return new AuditExecutor(core, max, queue, keepAlive.toSeconds());
    }

    @Bean
    AuditAspect auditAspect(
            AuditRepository repository,
            AuditExecutor executor,
            MeterRegistry meterRegistry,
            @Value("${kwikquant.audit.async:true}") boolean async) {
        return new AuditAspect(repository, executor, meterRegistry, async);
    }

    @Bean
    FilterRegistrationBean<LoggingFilter> loggingFilter() {
        FilterRegistrationBean<LoggingFilter> reg = new FilterRegistrationBean<>(new LoggingFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }
}
