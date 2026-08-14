package com.kwikquant.ai.application;

import com.kwikquant.shared.types.LlmProvider;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 各 provider 默认模型配置（替代各 adapter 内硬编码 {@code DEFAULT_MODEL} 常量）。
 *
 * <p>yaml：
 * <pre>{@code
 * kwikquant:
 *   ai:
 *     default-model:
 *       OPENAI: gpt-4o
 *       ANTHROPIC: claude-sonnet-4-20250514
 * }</pre>
 *
 * <p>按 provider 取默认模型名，无配置返 {@code null}。OPENAI/ANTHROPIC 在 yaml 配；
 * OPENAI_COMPATIBLE 不配 → {@code null} → adapter 报 model required（兼容协议 DeepSeek/通义等无统一
 * 默认模型，必须由用户传入）。
 */
@ConfigurationProperties(prefix = "kwikquant.ai")
public record LlmProperties(Map<LlmProvider, String> defaultModel) {

    public LlmProperties {
        if (defaultModel == null) {
            defaultModel = Map.of();
        }
    }
}
