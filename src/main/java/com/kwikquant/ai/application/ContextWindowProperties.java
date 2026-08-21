package com.kwikquant.ai.application;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 上下文窗口预算配置（驱动 {@link ContextWindowManager} 的窗口解析）。
 *
 * <p>yaml：
 * <pre>{@code
 * kwikquant:
 *   ai:
 *     context-window:
 *       default-tokens: 32000              # 模型未命中 by-model 时的窗口大小
 *       by-model:                           # 模型名 contains key → 该窗口大小
 *         gpt-4o: 128000
 *         claude: 200000
 * }</pre>
 *
 * <p><b>解析规则</b>：{@code resolveWindow(model)} 遍历 {@code byModel}，model 名 contains 某个 key
 * 即取其值（如 {@code "gpt-4o-mini"} contains {@code "gpt-4o"} → 128000）；都不命中取 {@code defaultTokens}。
 */
@ConfigurationProperties(prefix = "kwikquant.ai.context-window")
public record ContextWindowProperties(Integer defaultTokens, Map<String, Integer> byModel) {

    /** 默认窗口（未知模型的保守上限）。 */
    private static final int DEFAULT_TOKENS = 32_000;

    public ContextWindowProperties {
        if (defaultTokens == null) {
            defaultTokens = DEFAULT_TOKENS;
        }
        if (byModel == null) {
            byModel = Map.of();
        }
    }
}
