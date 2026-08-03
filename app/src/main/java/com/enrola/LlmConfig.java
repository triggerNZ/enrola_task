package com.enrola;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LlmConfig {

    @Bean
    ChatModel chatModel(
            @Value("${openai.api-key}") String apiKey,
            @Value("${openai.model}") String model,
            @Value("${openai.max-completion-tokens}") int maxCompletionTokens,
            @Value("${openai.timeout}") Duration timeout) {

        // maxCompletionTokens, not maxTokens: the latter maps to the legacy
        // `max_tokens` field, which the gpt-5 family rejects.
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .maxCompletionTokens(maxCompletionTokens)
                .timeout(timeout)
                .build();
    }
}
