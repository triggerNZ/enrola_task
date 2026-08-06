package com.enrola.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.agent.tools.ArrangeCallbackTool;
import com.enrola.agent.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The exchange on its own: what the model is sent, what comes back, and what is committed. No
 * Spring and no database -- the memory store here is langchain4j's in-memory one, so these run
 * in milliseconds. The Postgres-backed store is covered by {@code PostgresChatMemoryStoreTest}.
 */
class ChatAgentTest {

    private final ChatMemoryStore store = new InMemoryChatMemoryStore();
    private final UUID id = UUID.randomUUID();
    private final RecordingCallbacks callbacks = new RecordingCallbacks();

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    /** What a conversation was pinned to; passed in per call now, not held by the agent. */
    private static final Prompts PROMPTS = new Prompts("BRIEF", "Write the opening.");
    private static final Instant SLOT =
            LocalDateTime.parse("2026-08-06T14:15").atZone(SYDNEY).toInstant();

    /** Thursday 6 August 2026, 9am Sydney -- so "right now" in the prompt is assertable. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDateTime.parse("2026-08-06T09:00").atZone(SYDNEY).toInstant(), SYDNEY);

    private static final Recipient SWITCHER =
            new Recipient("Sam", "QLD", "Bupa", new BigDecimal("250.00"));
    private static final Recipient FIRST_TIMER = new Recipient("Alex", "VIC", null, null);

    /** A model with a script: each call takes the next response, repeating the last one. */
    private static class Scripted implements ChatModel {
        private final Deque<AiMessage> responses = new ArrayDeque<>();
        final List<List<ChatMessage>> requests = new ArrayList<>();
        int calls;

        Scripted(AiMessage... script) {
            for (AiMessage message : script) {
                responses.add(message);
            }
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(List.copyOf(request.messages()));
            calls++;
            AiMessage next = responses.size() > 1 ? responses.poll() : responses.peek();
            return ChatResponse.builder().aiMessage(next).build();
        }

        List<ChatMessage> lastRequest() {
            return requests.get(requests.size() - 1);
        }
    }

    /** Books whatever it is asked for, and records what that was. */
    private static class RecordingCallbacks implements CallbackTool {
        Object memoryId;
        Instant startsAt;
        String theirWords;
        String topic;
        int calls;

        @Override
        public BookingOutcome arrangeCallback(
                Object memoryId, Instant startsAt, String theirWords, String topic) {
            this.memoryId = memoryId;
            this.startsAt = startsAt;
            this.theirWords = theirWords;
            this.topic = topic;
            this.calls++;
            return new BookingOutcome.Booked(startsAt.atZone(SYDNEY));
        }

        @Override
        public BookingOutcome.Unavailable nextAvailable(LocalDate from, int count) {
            return new BookingOutcome.Unavailable(
                    "", List.of(SLOT.atZone(SYDNEY), SLOT.plusSeconds(900).atZone(SYDNEY)));
        }
    }

    /** A model that must not be called; failing here is the assertion. */
    private static final ChatModel FORBIDDEN =
            new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    throw new AssertionError("The model was called when it should not have been.");
                }
            };

    private ChatAgent agent(ChatModel model) {
        return agent(model, 2);
    }

    private ChatAgent agent(ChatModel model, int maxParts) {
        return new ChatAgent(
                model,
                store,
                ToolRegistry.of(new ArrangeCallbackTool(callbacks, SYDNEY)),
                20,
                maxParts,
                "Anna",
                "Comparato",
                FIXED_CLOCK);
    }

    private static ToolExecutionRequest callback(String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name("arrange_callback")
                .arguments(arguments)
                .build();
    }

    @Test
    void sendsHistoryThenTheNewMessageAndCommitsBothSides() {
        Scripted model = new Scripted(AiMessage.from("first answer"), AiMessage.from("second answer"));
        ChatAgent agent = agent(model);

        assertThat(agent.respondTo(id, "first", SWITCHER, PROMPTS).text()).isEqualTo("first answer");
        agent.respondTo(id, "second", SWITCHER, PROMPTS);

        assertThat(model.lastRequest())
                .containsExactly(
                        model.lastRequest().get(0), // the system message, asserted elsewhere
                        UserMessage.from("first"),
                        AiMessage.from("first answer"),
                        UserMessage.from("second"));
        assertThat(agent.history(id))
                .containsExactly(
                        UserMessage.from("first"),
                        AiMessage.from("first answer"),
                        UserMessage.from("second"),
                        AiMessage.from("second answer"));
    }

    @Test
    @DisplayName("a failed call commits nothing, so the next turn is not preceded by a ghost")
    void aFailedCallCommitsNothing() {
        ChatModel broken =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        throw new IllegalStateException("upstream is down");
                    }
                };

        assertThatThrownBy(() -> agent(broken).respondTo(id, "hello", SWITCHER, PROMPTS))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("upstream is down");

        assertThat(agent(broken).history(id)).isEmpty();
    }

    @Test
    @DisplayName("the prompt leads every request and is never written to the transcript")
    void systemPromptIsSentNotStored() {
        Scripted model = new Scripted(AiMessage.from("ok"));
        ChatAgent agent = agent(model);

        agent.respondTo(id, "first", SWITCHER, PROMPTS);
        agent.respondTo(id, "second", SWITCHER, PROMPTS);

        assertThat(model.requests)
                .allSatisfy(
                        request ->
                                assertThat(request.get(0))
                                        .isInstanceOf(SystemMessage.class)
                                        .satisfies(m -> assertThat(((SystemMessage) m).text()).startsWith("BRIEF")));
        // Editing the brief must not append a second system message to a live conversation,
        // and a multi-kilobyte FAQ has no business in every transcript.
        assertThat(agent.history(id)).noneMatch(SystemMessage.class::isInstance);
    }

    @Test
    @DisplayName("the agent is told who it is, so it stops introducing itself differently each run")
    void identityIsInEveryPrompt() {
        Scripted model = new Scripted(AiMessage.from("ok"));
        ChatAgent agent = agent(model);

        agent.open(id, SWITCHER, PROMPTS);
        agent.respondTo(id, "hello", SWITCHER, PROMPTS);

        assertThat(model.requests)
                .allSatisfy(
                        request ->
                                assertThat(((SystemMessage) request.get(0)).text())
                                        .contains("You are Anna, from Comparato."));
    }

    @Test
    @DisplayName("the recipient's details ride along on every turn, not just the first")
    void recipientFactsAreSentEveryTurn() {
        Scripted model = new Scripted(AiMessage.from("ok"));
        ChatAgent agent = agent(model);

        agent.respondTo(id, "first", SWITCHER, PROMPTS);
        agent.respondTo(id, "second", SWITCHER, PROMPTS);

        assertThat(model.requests)
                .allSatisfy(
                        request ->
                                assertThat(((SystemMessage) request.get(0)).text())
                                        .contains("Name: Sam")
                                        .contains("State: QLD")
                                        .contains("Currently with: Bupa")
                                        .contains("$250/month"));
    }

    @Test
    @DisplayName("someone with no cover is described as a first-timer, not as a null")
    void firstTimerFactsReadAsAnAbsenceOfCover() {
        Scripted model = new Scripted(AiMessage.from("ok"));

        agent(model).respondTo(id, "hi", FIRST_TIMER, PROMPTS);

        assertThat(((SystemMessage) model.lastRequest().get(0)).text())
                .contains("Currently with: no cover yet")
                .contains("Paying: n/a")
                .doesNotContain("null");
    }

    @Test
    @DisplayName("the opening commits only the agent's message, not the instruction behind it")
    void openingStartsTheTranscriptWithTheAgent() {
        Scripted model = new Scripted(AiMessage.from("Hi Sam, saw you were comparing cover."));

        Reply opening = agent(model).open(id, SWITCHER, PROMPTS);

        assertThat(opening.text()).startsWith("Hi Sam, saw you were comparing cover.");
        assertThat(model.lastRequest().get(1)).isEqualTo(UserMessage.from("Write the opening."));
        assertThat(agent(model).history(id)).containsExactly(AiMessage.from(opening.text()));
    }

    @Test
    @DisplayName("the opening is one SMS, whatever the rest of the conversation is allowed")
    void theOpeningIsAlwaysASingleMessage() {
        // Well over a single message, and over three, so neither budget would save it.
        String rambling = "We have great cover options for you to consider today. ".repeat(12);
        Scripted model = new Scripted(AiMessage.from(rambling));

        Reply opening = agent(model, 3).open(id, SWITCHER, PROMPTS);

        assertThat(opening.segments()).isEqualTo(1);
        // One retry was asked for, and the trim caught what the retry did not.
        assertThat(model.calls).isEqualTo(2);
        assertThat(agent(model, 3).history(id)).containsExactly(AiMessage.from(opening.text()));
    }

    @Test
    @DisplayName("the opt-out line is added by us, and survives the trim that removes everything else")
    void theOptOutFooterIsAlwaysPresent() {
        Scripted verbose = new Scripted(AiMessage.from("Hello there. ".repeat(30)));
        Scripted brief = new Scripted(AiMessage.from("Hi Sam, quick one about your cover?"));

        assertThat(agent(verbose, 3).open(id, SWITCHER, PROMPTS).text()).endsWith("\nReply 'stop' to opt out");
        assertThat(agent(brief, 3).open(UUID.randomUUID(), SWITCHER, PROMPTS).text())
                .endsWith("\nReply 'stop' to opt out");
    }

    @Test
    @DisplayName("an emoji in the opening does not sneak it past the one-SMS cap")
    void anEmojiOpeningIsStillASingleMessage() {
        // 120 characters is comfortably one GSM-7 message, but the emoji forces UCS-2, where a
        // single message holds 70 units -- so this needs cutting even though it "looks" short.
        Scripted model = new Scripted(AiMessage.from("Hi Sam, we help Bupa members find better value 🙂 " + "x".repeat(70)));

        Reply opening = agent(model, 3).open(id, SWITCHER, PROMPTS);

        assertThat(opening.segments()).isEqualTo(1);
        assertThat(opening.text()).endsWith("\nReply 'stop' to opt out");
    }

    @Test
    @DisplayName("the opening's budget leaves room for the footer, and is tighter than a reply's")
    void theOpeningIsGivenATighterBudget() {
        Scripted model = new Scripted(AiMessage.from("ok"));
        ChatAgent agent = agent(model, 3);

        agent.open(id, SWITCHER, PROMPTS);
        String openingPrompt = ((SystemMessage) model.lastRequest().get(0)).text();
        agent.respondTo(id, "hello", SWITCHER, PROMPTS);
        String replyPrompt = ((SystemMessage) model.lastRequest().get(0)).text();

        assertThat(openingPrompt).contains("under 136 characters"); // 160 less the footer
        assertThat(replyPrompt).contains("under 459 characters"); // three concatenated parts
    }

    @Test
    @DisplayName("a tool call is executed, then the model gets the result and confirms")
    void arrangesTheCallbackAndConfirms() {
        Scripted model =
                new Scripted(
                        AiMessage.from(callback("{\"starts_at\":\"2026-08-06T14:15\",\"their_words\":\"tomorrow arvo\",\"topic\":\"price\"}")),
                        AiMessage.from("No worries, someone will call tomorrow arvo."));
        ChatAgent agent = agent(model);

        Reply reply = agent.respondTo(id, "yeah call me tomorrow arvo", SWITCHER, PROMPTS);

        assertThat(callbacks.calls).isEqualTo(1);
        assertThat(callbacks.startsAt).isEqualTo(SLOT);
        assertThat(callbacks.theirWords).isEqualTo("tomorrow arvo");
        assertThat(callbacks.topic).isEqualTo("price");
        assertThat(reply.text()).isEqualTo("No worries, someone will call tomorrow arvo.");

        // The tool round trip has to survive: the model must see its own call and the result
        // on the next turn, or it will book the callback all over again.
        assertThat(agent.history(id))
                .hasSize(4)
                .satisfies(history -> assertThat(history.get(2)).isInstanceOf(ToolExecutionResultMessage.class));
        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("a callback with no time asks rather than booking an empty slot")
    void toolWithoutATimeIsRefused() {
        Scripted model =
                new Scripted(AiMessage.from(callback("{}")), AiMessage.from("When suits you?"));

        Reply reply = agent(model).respondTo(id, "call me", SWITCHER, PROMPTS);

        assertThat(callbacks.calls).isZero();
        assertThat(reply.text()).isEqualTo("When suits you?");
    }

    @Test
    @DisplayName("unreadable tool arguments do not blow up the turn")
    void malformedToolArgumentsAreHandled() {
        Scripted model =
                new Scripted(AiMessage.from(callback("not json")), AiMessage.from("When suits you?"));

        assertThat(agent(model).respondTo(id, "call me", SWITCHER, PROMPTS).text()).isEqualTo("When suits you?");
        assertThat(callbacks.calls).isZero();
    }

    @Test
    @DisplayName("a model that only ever calls tools is cut off rather than looping")
    void theToolLoopIsBounded() {
        Scripted model = new Scripted(AiMessage.from(callback("{\"starts_at\":\"2026-08-06T14:15\"}")));

        Reply reply = agent(model).respondTo(id, "call me", SWITCHER, PROMPTS);

        // Five: enough for two lookups and a brevity retry, then it is cut off.
        assertThat(model.calls).isEqualTo(5);
        assertThat(reply.text()).contains("stuck");
        // Nothing committed: the next turn starts from clean history rather than from a
        // transcript full of the same tool call three times over.
        assertThat(agent(model).history(id)).isEmpty();
    }

    @Test
    @DisplayName("an overlong reply is asked for again, and only the short one is kept")
    void tooLongIsRetried() {
        String tooLong = "word ".repeat(80).strip();
        Scripted model = new Scripted(AiMessage.from(tooLong), AiMessage.from("Short answer."));
        ChatAgent agent = agent(model);

        Reply reply = agent.respondTo(id, "explain it all", SWITCHER, PROMPTS);

        assertThat(reply.text()).isEqualTo("Short answer.");
        assertThat(reply.segments()).isEqualTo(1);
        assertThat(model.calls).isEqualTo(2);
        assertThat(agent.history(id)).doesNotContain(AiMessage.from(tooLong));
    }

    @Test
    @DisplayName("a model that will not be brief is trimmed to the part limit")
    void stillTooLongIsTrimmed() {
        String tooLong = "word ".repeat(200).strip();
        Scripted model = new Scripted(AiMessage.from(tooLong));

        Reply reply = agent(model, 2).respondTo(id, "explain it all", SWITCHER, PROMPTS);

        assertThat(reply.segments()).isEqualTo(2);
    }

    @Test
    @DisplayName("a turn the model never saw is still written down")
    void recordWritesBothSidesWithoutCallingTheModel() {
        ChatAgent agent = agent(FORBIDDEN);

        agent.record(id, "STOP", "You're unsubscribed.");

        assertThat(agent.history(id))
                .containsExactly(UserMessage.from("STOP"), AiMessage.from("You're unsubscribed."));
    }

    @Test
    @DisplayName("a window of one could hold a question but not its answer")
    void rejectsAWindowTooSmallToHoldATurn() {
        assertThatThrownBy(
                        () ->
                                new ChatAgent(
                                        FORBIDDEN,
                                        store,
                                        ToolRegistry.of(new ArrangeCallbackTool(callbacks, SYDNEY)),
                                        1,
                                        2,
                                        "Anna",
                                        "Comparato",
                                        FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat.max-messages");
    }
}
