package com.kwikquant.shared.infra;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动期把 {@code kwikquant.outbound.allow-private-hosts} 注入 {@link OutboundUrlPolicy} 静态豁免集。
 *
 * <p>默认空集 = SSRF 全禁(HTTPS+公网)。dev/self-host 配置 localhost/127.0.0.1/::1 等放行本地
 * LLM 网关(Ollama/vLLM/LiteLLM)。prod SaaS 不配置即保持全禁。
 */
@Component
public class OutboundPolicyConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboundPolicyConfigurer.class);

    private final String allowListCsv;

    public OutboundPolicyConfigurer(@Value("${kwikquant.outbound.allow-private-hosts:}") String allowListCsv) {
        this.allowListCsv = allowListCsv;
    }

    @PostConstruct
    void apply() {
        Set<String> hosts = Arrays.stream(allowListCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        OutboundUrlPolicy.configureAllowedPrivateHosts(hosts);
        if (!hosts.isEmpty()) {
            log.warn(
                    "outbound SSRF policy relaxed for private hosts {} (self-host/dev only;prod must not configure this)",
                    hosts);
        }
    }
}
