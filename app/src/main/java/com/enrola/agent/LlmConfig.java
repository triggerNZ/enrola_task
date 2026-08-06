package com.enrola.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class LlmConfig {

    @Bean
    ChatModel chatModel(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model}") String model,
            @Value("${openai.max-completion-tokens}") int maxCompletionTokens,
            @Value("${openai.timeout}") Duration timeout) {

        // Fail at startup rather than accepting requests that cannot be served: a server
        // that is listening but answers every chat with a 502 is worse than one that
        // refuses to come up and says why.
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "No API key. Set OPENAI_API_KEY in the environment or .env.");
        }

        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .maxCompletionTokens(maxCompletionTokens)
                .timeout(timeout)
                .build();
    }

}
