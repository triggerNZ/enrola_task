package com.enrola;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive chat loop. Reads a turn at a time from stdin and ends on EOF
 * (Ctrl-D), after which the application shuts down.
 */
@Component
public class ChatRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChatRunner.class);

    private final ChatModel chatModel;
    private final String apiKey;
    private int exitCode = 0;

    ChatRunner(ChatModel chatModel, @Value("${openai.api-key}") String apiKey) {
        this.chatModel = chatModel;
        this.apiKey = apiKey;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!StringUtils.hasText(apiKey)) {
            log.error("No API key. Set OPENAI_API_KEY in the environment or .env.");
            exitCode = 1;
            return;
        }

        // The whole history is resent every turn -- that is what gives the model
        // context from earlier in the conversation.
        List<ChatMessage> history = new ArrayList<>();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        System.out.println("Chat started. Ctrl-D to end the conversation.");

        while (true) {
            System.out.print("\nyou> ");
            System.out.flush();

            String line = in.readLine();
            if (line == null) { // Ctrl-D / EOF
                System.out.printf("%nConversation ended after %d turn(s).%n", history.size() / 2);
                return;
            }
            if (!StringUtils.hasText(line)) {
                continue;
            }

            history.add(UserMessage.from(line));
            try {
                ChatResponse response = chatModel.chat(history);
                AiMessage reply = response.aiMessage();
                history.add(reply);
                System.out.println("\nllm> " + reply.text());
                exitCode = 0;
            } catch (RuntimeException e) {
                // Drop the unanswered turn so the history stays a clean
                // user/assistant alternation for the next request.
                history.remove(history.size() - 1);
                log.error("LLM call failed: {}", e.getMessage());
                exitCode = 1;
            }
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
