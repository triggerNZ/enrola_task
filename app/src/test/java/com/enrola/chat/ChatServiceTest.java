package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole flow against a real Postgres, with only the model stubbed: outreach opens a
 * conversation, replies move the lead along, the callback tool closes it, and the two cases that
 * must never reach the model do not.
 *
 * <p>The exchange itself is covered without a database in {@code ChatAgentTest}; what this adds
 * is the real store and the lead pipeline around it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"openai.api-key=test-key", "admin.password=test"})
@Import(ChatServiceTest.StubModel.class)
class ChatServiceTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    @Autowired private ChatService chat;
    @Autowired private LeadService leadService;
    @Autowired private LeadRepository leads;

    @BeforeEach
    void resetModel() {
        StubModel.script = messages -> AiMessage.from("echo: " + lastText(messages));
        StubModel.calls = 0;
    }

    private static String lastText(List<ChatMessage> messages) {
        ChatMessage last = messages.get(messages.size() - 1);
        return last instanceof UserMessage user ? user.singleText() : "?";
    }

    /**
     * Asks for a callback the first time and confirms once the tool has run. Keyed on the last
     * message rather than a call count, so it does not depend on how many calls outreach made.
     */
    private static StubModel.Script booksACallback(String startsAt) {
        return messages ->
                messages.get(messages.size() - 1) instanceof ToolExecutionResultMessage
                        ? AiMessage.from("Done, someone will call then.")
                        : AiMessage.from(
                                ToolExecutionRequest.builder()
                                        .id("c1")
                                        .name("arrange_callback")
                                        .arguments(
                                                "{\"starts_at\":\"%s\",\"their_words\":\"tomorrow arvo\"}"
                                                        .formatted(startsAt))
                                        .build());
    }

    /**
     * Replaces the OpenAI model so the tests need no network. {@code script} is swapped per test
     * to drive a particular reply, and {@code calls} is what proves the model was left alone.
     */
    @TestConfiguration
    static class StubModel {

        interface Script {
            AiMessage reply(List<ChatMessage> messages);
        }

        static volatile Script script;
        static volatile int calls;

        /** Thursday 6 August 2026, 9am Sydney: a weekday morning, so the afternoon is bookable. */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(
                    LocalDateTime.parse("2026-08-06T09:00").atZone(SYDNEY).toInstant(), SYDNEY);
        }

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    calls++;
                    return ChatResponse.builder().aiMessage(script.reply(request.messages())).build();
                }
            };
        }
    }

    private Lead switcher() {
        return leadService.create(
                "Sam", "+61400000000", "sam@example.com", "qld", "Bupa", new BigDecimal("250.00"), true);
    }

    private UUID outreachTo(Lead lead) {
        return leadService.startOutreach(lead.id()).conversationId();
    }

    @Test
    @DisplayName("outreach starts the transcript with the agent, and the lead is now waiting")
    void outreachOpensTheConversation() {
        Lead lead = switcher();

        LeadService.Outreach outreach = leadService.startOutreach(lead.id());

        assertThat(outreach.opening().text()).startsWith("echo:");
        assertThat(chat.transcript(outreach.conversationId()))
                .singleElement()
                .extracting(MessageView::type)
                .isEqualTo("AI");
        assertThat(leads.find(lead.id())).get().extracting(Lead::status).isEqualTo(Lead.AWAITING_REPLY);
    }

    @Test
    @DisplayName("the state is normalised on the way in, so QLD is QLD however it was typed")
    void stateIsNormalised() {
        assertThat(switcher().state()).isEqualTo("QLD");
        assertThatThrownBy(
                        () ->
                                leadService.create(
                                        "Sam", "+61400000000", null, "Queensland", null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nobody is texted twice, and nobody is texted without consenting")
    void outreachRefusesTheMistakesThatCannotBeUndone() {
        Lead contacted = switcher();
        outreachTo(contacted);
        assertThatThrownBy(() -> leadService.startOutreach(contacted.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been contacted");

        Lead noConsent =
                leadService.create("Nope", "+61400999999", null, "VIC", null, null, false);
        assertThatThrownBy(() -> leadService.startOutreach(noConsent.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not consented");
    }

    @Test
    void replyingMovesTheLeadToEngaged() {
        Lead lead = switcher();
        UUID conversationId = outreachTo(lead);

        chat.send(conversationId, "how long are the waiting periods?");

        assertThat(leads.find(lead.id())).get().extracting(Lead::status).isEqualTo(Lead.ENGAGED);
        assertThat(chat.transcript(conversationId)).hasSize(3); // opening, question, answer
    }

    @Test
    @DisplayName("the agent's callback tool closes the conversation and hands the lead off")
    void arrangingACallbackClosesTheConversation() {
        Lead lead = switcher();
        UUID conversationId = outreachTo(lead);

        StubModel.script = booksACallback("2026-08-06T14:15");

        ChatService.Outcome outcome = chat.send(conversationId, "yeah give me a call");

        assertThat(outcome.reply().text()).isEqualTo("Done, someone will call then.");
        assertThat(outcome.conversation().closed()).isTrue();
        assertThat(outcome.conversation().closedReason()).isEqualTo(ConversationSummary.CALLBACK);
        assertThat(outcome.conversation().closingNote()).isEqualTo("Thu 6 Aug, 2:15PM AEST");
        assertThat(leads.find(lead.id())).get().extracting(Lead::status).isEqualTo(Lead.HANDED_OFF);
    }

    @Test
    @DisplayName("a message after handoff is answered without paying for a model call")
    void aClosedConversationDoesNotReachTheModel() {
        UUID conversationId = outreachTo(switcher());
        StubModel.script = booksACallback("2026-08-06T15:00");
        chat.send(conversationId, "call me");

        int before = StubModel.calls;
        ChatService.Outcome outcome = chat.send(conversationId, "actually one more thing");

        // A consultant owns the relationship now; an agent still selling would be worse than
        // silence, and would cost a call per message to be so.
        assertThat(StubModel.calls).isEqualTo(before);
        assertThat(outcome.reply().text()).contains("consultant");
        // Still written down, so whoever picks the conversation up sees what they said.
        assertThat(chat.transcript(conversationId))
                .extracting(MessageView::text)
                .contains("actually one more thing");
    }

    @Test
    @DisplayName("STOP is honoured immediately, without a model call")
    void optingOutDoesNotReachTheModel() {
        Lead lead = switcher();
        UUID conversationId = outreachTo(lead);

        int before = StubModel.calls;
        ChatService.Outcome outcome = chat.send(conversationId, "STOP");

        assertThat(StubModel.calls).isEqualTo(before);
        assertThat(outcome.reply().text()).contains("unsubscribed");
        assertThat(outcome.conversation().closedReason()).isEqualTo(ConversationSummary.OPTED_OUT);
        assertThat(leads.find(lead.id())).get().extracting(Lead::status).isEqualTo(Lead.OPTED_OUT);
    }

    @Test
    @DisplayName("opting out is recognised however it is typed")
    void optOutIsCaseAndPunctuationInsensitive() {
        for (String word : List.of("stop", " Stop. ", "UNSUBSCRIBE", "opt out")) {
            UUID conversationId = outreachTo(switcher());
            assertThat(chat.send(conversationId, word).conversation().closedReason())
                    .as("%s should opt out", word)
                    .isEqualTo(ConversationSummary.OPTED_OUT);
        }
    }

    @Test
    @DisplayName("an opted-out lead is not contacted again")
    void outreachRefusesAfterOptOut() {
        Lead lead = switcher();
        chat.send(outreachTo(lead), "STOP");

        assertThatThrownBy(() -> leadService.startOutreach(lead.id()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void unknownConversationsAndLeadsAreRejected() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> chat.send(missing, "hello"))
                .isInstanceOf(UnknownConversationException.class);
        assertThatThrownBy(() -> leadService.get(missing)).isInstanceOf(UnknownLeadException.class);
    }

    @Test
    void blankMessagesAreRejectedBeforeTheModelIsCalled() {
        UUID conversationId = outreachTo(switcher());

        int before = StubModel.calls;
        assertThatThrownBy(() -> chat.send(conversationId, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StubModel.calls).isEqualTo(before);
    }
}
