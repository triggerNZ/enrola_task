package com.enrola;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Interactive chat loop. Reads a turn at a time from stdin and ends on EOF (Ctrl-D), after
 * which the application shuts down.
 *
 * <p>History lives in Postgres, so a conversation survives the process. Each run starts a new
 * conversation unless {@code --conversation=<uuid>} or {@code --resume} says otherwise.
 */
@Component
public class ChatRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChatRunner.class);
    private static final int TITLE_MAX_LENGTH = 60;

    private final ChatModel chatModel;
    private final ChatMemoryStore memoryStore;
    private final ConversationRepository conversations;
    private final String apiKey;
    private final int maxMessages;
    private final String systemPrompt;

    private int exitCode = 0;

    ChatRunner(
            ChatModel chatModel,
            ChatMemoryStore memoryStore,
            ConversationRepository conversations,
            @Value("${openai.api-key}") String apiKey,
            @Value("${chat.max-messages}") int maxMessages,
            @Value("${chat.system-prompt:}") String systemPrompt) {
        this.chatModel = chatModel;
        this.memoryStore = memoryStore;
        this.conversations = conversations;
        this.apiKey = apiKey;
        this.maxMessages = maxMessages;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!StringUtils.hasText(apiKey)) {
            log.error("No API key. Set OPENAI_API_KEY in the environment or .env.");
            exitCode = 1;
            return;
        }
        if (maxMessages < 2) {
            // A window of one cannot hold a question and its answer, and it also leaves the
            // store unable to tell a new message from one it has already seen.
            log.error("chat.max-messages must be at least 2, got {}.", maxMessages);
            exitCode = 1;
            return;
        }

        Optional<UUID> resolved = resolveConversation(args);
        if (resolved.isEmpty()) {
            exitCode = 1;
            return;
        }
        UUID conversationId = resolved.get();
        conversations.touch(conversationId);

        var memory =
                MessageWindowChatMemory.builder()
                        .id(conversationId)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(memoryStore)
                        .build();

        if (StringUtils.hasText(systemPrompt)) {
            // An identical prompt on a resumed conversation is ignored by the memory itself.
            memory.add(SystemMessage.from(systemPrompt));
        }

        int restored = conversations.messageCount(conversationId);
        System.out.printf(
                "Conversation %s  (%d message(s) restored). Ctrl-D to end.%n", conversationId, restored);

        int turns = 0;
        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        while (true) {
            System.out.print("\nyou> ");
            System.out.flush();

            String line = in.readLine();
            if (line == null) { // Ctrl-D / EOF
                break;
            }
            if (!StringUtils.hasText(line)) {
                continue;
            }

            if (turns == 0) {
                conversations.setTitleIfAbsent(conversationId, truncateTitle(line));
            }
            if (exchange(memory, line)) {
                turns++;
            }
        }

        conversations.touch(conversationId);
        System.out.printf("%nConversation ended after %d turn(s).%n", turns);
        System.out.printf("Resume with: ./chat.sh --conversation=%s%n", conversationId);
    }

    /** Returns true if the turn completed and was persisted. */
    private boolean exchange(MessageWindowChatMemory memory, String line) {
        UserMessage user = UserMessage.from(line);

        // Build the request without committing the user message first. The store is
        // append-only, so a message added before a failed call could not be taken back --
        // ChatMemory has no remove, and set(listWithoutLast) is a no-op because the
        // reconciler correctly finds nothing new to append. Deferring the write keeps the
        // transcript a clean user/assistant alternation, and halves the store round-trips.
        List<ChatMessage> request = new ArrayList<>(memory.messages());
        request.add(user);

        try {
            AiMessage reply = chatModel.chat(request).aiMessage();
            memory.add(user);
            memory.add(reply);
            System.out.println("\nllm> " + reply.text());
            exitCode = 0;
            return true;
        } catch (RuntimeException e) {
            log.error("LLM call failed: {}", e.getMessage());
            exitCode = 1;
            return false;
        }
    }

    /** Empty means the arguments were unusable and the caller should exit non-zero. */
    private Optional<UUID> resolveConversation(ApplicationArguments args) {
        boolean hasExplicit = args.containsOption("conversation");
        if (hasExplicit && args.containsOption("resume")) {
            log.warn("--conversation and --resume both given; using --conversation.");
        }

        if (hasExplicit) {
            List<String> values = args.getOptionValues("conversation");
            if (values == null || values.isEmpty() || !StringUtils.hasText(values.get(0))) {
                log.error("--conversation needs a value, e.g. --conversation=<uuid>.");
                return Optional.empty();
            }
            String raw = values.get(0);
            UUID id;
            try {
                id = UUID.fromString(raw);
            } catch (IllegalArgumentException e) {
                log.error("Not a conversation id: {}", raw);
                return Optional.empty();
            }
            // Fail rather than creating a conversation with the given id: a single mistyped
            // character would otherwise produce an empty conversation that looks like lost
            // history.
            if (!conversations.exists(id)) {
                log.error(
                        "No conversation {}. Start a new one with ./chat.sh, or list existing ones with:"
                                + " select id, title, last_used_at from conversation order by last_used_at desc;",
                        id);
                return Optional.empty();
            }
            return Optional.of(id);
        }

        if (args.containsOption("resume")) {
            Optional<UUID> recent = conversations.mostRecentWithMessages();
            if (recent.isPresent()) {
                return recent;
            }
            System.out.println("No previous conversation; starting a new one.");
        }

        return Optional.of(conversations.create(null));
    }

    private static String truncateTitle(String line) {
        String trimmed = line.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, TITLE_MAX_LENGTH - 1) + "…";
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
