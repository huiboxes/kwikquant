package com.kwikquant.strategy.infrastructure;

import com.kwikquant.shared.types.LlmProvider;
import org.springframework.stereotype.Component;

/** OpenAI 官方 API adapter。 */
@Component
class OpenAiAdapter extends AbstractOpenAiAdapter {

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.openai.com/v1";
    }

    @Override
    protected String defaultModel() {
        return "gpt-4o";
    }
}
