package com.enrola;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.UserMessage;
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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "openai.api-key=")
class ConversationRepositoryTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired private ConversationRepository conversations;
    @Autowired private PostgresChatMemoryStore store;

    @Test
    void createsAndFinds() {
        UUID id = conversations.create("a title");
        assertThat(conversations.exists(id)).isTrue();
        assertThat(conversations.exists(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("--resume skips conversations that never got a message")
    void mostRecentSkipsEmptyConversations() {
        UUID withMessages = conversations.create("used");
        store.updateMessages(withMessages, List.of(UserMessage.from("hello")));

        // Created after, so it wins on last_used_at -- but it has no messages, which is what
        // an immediate Ctrl-D leaves behind.
        UUID empty = conversations.create("never used");
        conversations.touch(empty);

        assertThat(conversations.mostRecentWithMessages()).contains(withMessages);
    }

    @Test
    void mostRecentFollowsLastUsedAt() {
        UUID older = conversations.create("older");
        store.updateMessages(older, List.of(UserMessage.from("a")));
        UUID newer = conversations.create("newer");
        store.updateMessages(newer, List.of(UserMessage.from("b")));

        conversations.touch(older); // older is now the most recently used
        assertThat(conversations.mostRecentWithMessages()).contains(older);
    }

    @Test
    void setTitleIfAbsentDoesNotOverwrite() {
        UUID id = conversations.create(null);
        conversations.setTitleIfAbsent(id, "first");
        conversations.setTitleIfAbsent(id, "second");

        assertThat(conversations.title(id)).contains("first");
    }

    @Test
    void messageCountRespectsTheClearWatermark() {
        UUID id = conversations.create(null);
        store.updateMessages(id, List.of(UserMessage.from("a"), UserMessage.from("b")));
        assertThat(conversations.messageCount(id)).isEqualTo(2);

        store.deleteMessages(id);
        assertThat(conversations.messageCount(id)).isZero();
        assertThat(store.storedRowCount(id)).isEqualTo(2); // rows still there
    }
}
