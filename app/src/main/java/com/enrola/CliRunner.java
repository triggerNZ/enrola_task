package com.enrola;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Entry point for the command-line work: runs once after the context is ready,
 * then the application shuts down.
 */
@Component
public class CliRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final ChatModel chatModel;
    private final String apiKey;
    private int exitCode = 0;

    CliRunner(ChatModel chatModel, @Value("${openai.api-key}") String apiKey) {
        this.chatModel = chatModel;
        this.apiKey = apiKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(apiKey)) {
            log.error("No API key. Set OPENAI_API_KEY in the environment, or pass --openai.api-key=<key>.");
            exitCode = 1;
            return;
        }

        // Any leftover arguments become the prompt, so `run --args="explain gradle"` works.
        String prompt = args.getNonOptionArgs().isEmpty()
                ? "In one sentence, what is a Spring Boot CommandLineRunner?"
                : String.join(" ", args.getNonOptionArgs());

        log.info("Prompt: {}", prompt);
        try {
            log.info("Response: {}", chatModel.chat(prompt));
        } catch (RuntimeException e) {
            log.error("LLM call failed: {}", e.getMessage());
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
