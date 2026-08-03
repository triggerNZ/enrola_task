package com.enrola;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the store against a real Postgres, with Flyway applying the real migration.
 *
 * <p>{@code disabledWithoutDocker} keeps {@code ./gradlew test} green on a machine with no
 * Docker; the reconciliation logic itself is covered without a database in
 * {@link MessageReconcilerTest}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "openai.api-key=")
class PostgresChatMemoryStoreTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired private PostgresChatMemoryStore store;
    @Autowired private ConversationRepository conversations;

    private UUID newConversation() {
        return conversations.create("test");
    }

    @Test
    void unknownConversationReturnsEmptyNotNull() {
        assertThat(store.getMessages(UUID.randomUUID())).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("messages survive the jsonb round trip with equality intact")
    void roundTripsMessages() {
        UUID id = newConversation();
        List<ChatMessage> messages =
                List.of(SystemMessage.from("be terse"), UserMessage.from("hello"), AiMessage.from("hi"));

        store.updateMessages(id, messages);

        // Equality is what the reconciler depends on, and Postgres normalises jsonb on write
        // -- so this asserts more than it appears to.
        assertThat(store.getMessages(id)).containsExactlyElementsOf(messages);
    }

    @Test
    void roundTripsToolCalls() {
        UUID id = newConversation();
        AiMessage call =
                AiMessage.from(
                        ToolExecutionRequest.builder().id("call-1").name("lookup").arguments("{\"q\":1}").build());
        List<ChatMessage> messages =
                List.of(
                        UserMessage.from("look it up"),
                        call,
                        ToolExecutionResultMessage.from("call-1", "lookup", "42"));

        store.updateMessages(id, messages);

        assertThat(store.getMessages(id)).containsExactlyElementsOf(messages);
    }

    @Test
    @DisplayName("the window trims but the transcript keeps everything")
    void transcriptOutgrowsTheWindow() {
        UUID id = newConversation();
        var memory =
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(4)
                        .chatMemoryStore(store)
                        .build();

        for (int i = 0; i < 6; i++) {
            memory.add(UserMessage.from("q" + i));
            memory.add(AiMessage.from("r" + i));
        }

        assertThat(memory.messages()).hasSize(4); // what the model would receive
        assertThat(store.getMessages(id)).hasSize(12); // what Postgres kept
        assertThat(store.storedSequences(id))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11); // dense, no gaps

        assertThat(store.getMessages(id).get(0)).isEqualTo(UserMessage.from("q0"));
        assertThat(store.getMessages(id).get(11)).isEqualTo(AiMessage.from("r5"));
    }

    @Test
    @DisplayName("resuming a conversation restores the full transcript")
    void resumeSeesEverything() {
        UUID id = newConversation();
        var first =
                MessageWindowChatMemory.builder().id(id).maxMessages(4).chatMemoryStore(store).build();
        for (int i = 0; i < 5; i++) {
            first.add(UserMessage.from("q" + i));
            first.add(AiMessage.from("r" + i));
        }

        // A separate memory instance, as a later process would build.
        var resumed =
                MessageWindowChatMemory.builder().id(id).maxMessages(4).chatMemoryStore(store).build();

        assertThat(resumed.messages()).hasSize(4);
        assertThat(store.getMessages(id)).hasSize(10);

        resumed.add(UserMessage.from("q5"));
        assertThat(store.getMessages(id)).hasSize(11);
        assertThat(store.storedSequences(id)).endsWith(10); // continues, does not restart
    }

    @Test
    @DisplayName("an identical system prompt on resume adds no rows")
    void identicalSystemPromptIsNotDuplicated() {
        UUID id = newConversation();
        var memory =
                MessageWindowChatMemory.builder().id(id).maxMessages(10).chatMemoryStore(store).build();
        memory.add(SystemMessage.from("be terse"));
        memory.add(UserMessage.from("hello"));
        int before = store.storedRowCount(id);

        var resumed =
                MessageWindowChatMemory.builder().id(id).maxMessages(10).chatMemoryStore(store).build();
        resumed.add(SystemMessage.from("be terse"));

        assertThat(store.storedRowCount(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("clear() empties the memory but keeps every row")
    void clearIsSoft() {
        UUID id = newConversation();
        var memory =
                MessageWindowChatMemory.builder().id(id).maxMessages(10).chatMemoryStore(store).build();
        memory.add(UserMessage.from("q0"));
        memory.add(AiMessage.from("r0"));

        memory.clear();

        assertThat(store.getMessages(id)).isEmpty(); // memory really is cleared
        assertThat(store.storedRowCount(id)).isEqualTo(2); // but nothing was destroyed

        // A later message continues the sequence rather than restarting at 0.
        memory.add(UserMessage.from("q1"));
        assertThat(store.storedRowCount(id)).isEqualTo(3);
        assertThat(store.storedSequences(id)).containsExactly(0, 1, 2);
        assertThat(store.getMessages(id)).containsExactly(UserMessage.from("q1"));
    }

    @Test
    @DisplayName("clearing an empty conversation does not resurrect earlier messages")
    void clearOnEmptyConversationKeepsWatermark() {
        UUID id = newConversation();
        var memory =
                MessageWindowChatMemory.builder().id(id).maxMessages(10).chatMemoryStore(store).build();
        memory.add(UserMessage.from("q0"));

        memory.clear();
        memory.clear(); // second clear has nothing to advance past

        assertThat(store.getMessages(id)).isEmpty();
    }

    @Test
    void rejectsNonUuidMemoryId() {
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> store.getMessages("default")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversation UUID");
    }
}
