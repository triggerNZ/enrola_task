package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.UserMessage;
import java.time.Instant;
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
@SpringBootTest(properties = {"openai.api-key=test-key", "admin.password=test"})
class ConversationRepositoryTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired private ConversationRepository conversations;
    @Autowired private PostgresChatMemoryStore store;
    @Autowired private LeadRepository leads;

    /** A conversation the agent is still answering: no lead, not closed. */
    private static ConversationSummary open(UUID id, String title, int messageCount) {
        return new ConversationSummary(id, null, title, messageCount, null, null, null);
    }

    @Test
    void createsAndFinds() {
        UUID id = conversations.create(null, "a title");
        assertThat(conversations.exists(id)).isTrue();
        assertThat(conversations.exists(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("--resume skips conversations that never got a message")
    void mostRecentSkipsEmptyConversations() {
        UUID withMessages = conversations.create(null, "used");
        store.updateMessages(withMessages, List.of(UserMessage.from("hello")));

        // Created after, so it wins on last_used_at -- but it has no messages, which is what
        // an immediate Ctrl-D leaves behind.
        UUID empty = conversations.create(null, "never used");
        conversations.touch(empty);

        assertThat(conversations.mostRecentWithMessages()).contains(withMessages);
    }

    @Test
    void mostRecentFollowsLastUsedAt() {
        UUID older = conversations.create(null, "older");
        store.updateMessages(older, List.of(UserMessage.from("a")));
        UUID newer = conversations.create(null, "newer");
        store.updateMessages(newer, List.of(UserMessage.from("b")));

        conversations.touch(older); // older is now the most recently used
        assertThat(conversations.mostRecentWithMessages()).contains(older);
    }

    @Test
    @DisplayName("the listing is newest first, carries the title and count, and honours the limit")
    void recentWithMessagesSummarises() {
        UUID older = conversations.create(null, "older");
        store.updateMessages(older, List.of(UserMessage.from("a")));
        UUID newer = conversations.create(null, "newer");
        store.updateMessages(newer, List.of(UserMessage.from("b"), UserMessage.from("c")));
        UUID empty = conversations.create(null, "empty"); // no messages: never listed

        // Other tests in this class share the database, so assert about these rows rather
        // than about the whole listing.
        assertThat(conversations.recentWithMessages(100))
                .contains(open(newer, "newer", 2))
                .contains(open(older, "older", 1))
                .extracting(ConversationSummary::id)
                .containsSubsequence(newer, older)
                .doesNotContain(empty);
        assertThat(conversations.recentWithMessages(1))
                .containsExactly(open(newer, "newer", 2));
    }

    @Test
    @DisplayName("the listed count is the visible transcript, not every row ever written")
    void recentWithMessagesRespectsTheClearWatermark() {
        UUID id = conversations.create(null, "cleared");
        store.updateMessages(id, List.of(UserMessage.from("a"), UserMessage.from("b")));
        store.deleteMessages(id);

        assertThat(conversations.recentWithMessages(100))
                .contains(open(id, "cleared", 0));
    }

    @Test
    void findReturnsTheSummaryOrNothing() {
        UUID id = conversations.create(null, "titled");
        store.updateMessages(id, List.of(UserMessage.from("a")));

        assertThat(conversations.find(id)).contains(open(id, "titled", 1));
        assertThat(conversations.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void setTitleIfAbsentDoesNotOverwrite() {
        UUID id = conversations.create(null, null);
        conversations.setTitleIfAbsent(id, "first");
        conversations.setTitleIfAbsent(id, "second");

        assertThat(conversations.title(id)).contains("first");
    }

    @Test
    void findsTheConversationBelongingToALead() {
        UUID leadId = leads.create("Sam", "+61400000000", null, "QLD", null, null, Instant.now());
        UUID conversationId = conversations.create(leadId, "outreach");

        assertThat(conversations.findIdByLead(leadId)).contains(conversationId);
        assertThat(conversations.findIdByLead(UUID.randomUUID())).isEmpty();
        assertThat(conversations.find(conversationId))
                .get()
                .extracting(ConversationSummary::leadId)
                .isEqualTo(leadId);
    }

    @Test
    @DisplayName("closing records why, and a second close does not overwrite the first")
    void closeIsOnceOnly() {
        UUID id = conversations.create(null, "talking");
        assertThat(conversations.find(id)).get().matches(c -> !c.closed());

        conversations.close(id, ConversationSummary.CALLBACK, "tomorrow arvo");
        conversations.close(id, ConversationSummary.OPTED_OUT, "STOP");

        assertThat(conversations.find(id))
                .get()
                .satisfies(
                        c -> {
                            assertThat(c.closed()).isTrue();
                            assertThat(c.closedReason()).isEqualTo(ConversationSummary.CALLBACK);
                            assertThat(c.closingNote()).isEqualTo("tomorrow arvo");
                        });
    }

    @Test
    void messageCountRespectsTheClearWatermark() {
        UUID id = conversations.create(null, null);
        store.updateMessages(id, List.of(UserMessage.from("a"), UserMessage.from("b")));
        assertThat(conversations.messageCount(id)).isEqualTo(2);

        store.deleteMessages(id);
        assertThat(conversations.messageCount(id)).isZero();
        assertThat(store.storedRowCount(id)).isEqualTo(2); // rows still there
    }
}
