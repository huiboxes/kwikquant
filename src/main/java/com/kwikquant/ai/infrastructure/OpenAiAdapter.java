package com.kwikquant.ai.infrastructure;

import com.kwikquant.ai.application.LlmProperties;
import com.kwikquant.shared.types.LlmProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** OpenAI 官方 API adapter。 */
@Component
class OpenAiAdapter extends AbstractOpenAiAdapter {

    OpenAiAdapter(WebClient llmWebClient, LlmProperties llmProperties) {
        super(llmWebClient, llmProperties);
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.OPENAI;
    }

    @Override
    protected String defaultBaseUrl() {
        return "https://api.openai.com/v1";
    }
}
