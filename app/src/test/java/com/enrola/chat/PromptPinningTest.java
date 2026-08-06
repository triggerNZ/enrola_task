package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * A conversation keeps the instructions it opened with.
 *
 * <p>This is the point of versioning them at all: editing a prompt has to change what the next
 * conversation is told without quietly rewriting what an earlier one was told, or the review
 * pages are judging conversations against instructions that never applied to them.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"openai.api-key=test-key", "admin.password=test"})
@Import(PromptPinningTest.RecordingModel.class)
class PromptPinningTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    /** Records the system prompt of every call, so we can see which version reached the model. */
    @TestConfiguration
    static class RecordingModel {
        static final List<String> systemPrompts = new ArrayList<>();

        @Bean
        @Primary
        ChatModel recordingChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    for (ChatMessage message : request.messages()) {
                        if (message instanceof SystemMessage system) {
                            systemPrompts.add(system.text());
                        }
                    }
                    return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                }
            };
        }
    }

    @Autowired private LeadService leadService;
    @Autowired private ChatService chat;
    @Autowired private PromptService prompts;

    private UUID openConversation(String name) {
        Lead lead =
                leadService.create(
                        name, "+61400000000", null, "NSW", "Bupa", new BigDecimal("250.00"), true);
        return leadService.startOutreach(lead.id()).conversationId();
    }

    @Test
    @DisplayName("opening a conversation records the version of every prompt it will run on")
    void outreachPinsTheCurrentSet() {
        UUID conversationId = openConversation("Pinned");

        assertThat(prompts.usedBy(conversationId))
                .extracting(Prompt::kind)
                .containsExactlyInAnyOrder(PromptKind.values());
    }

    @Test
    @DisplayName("editing a prompt afterwards does not change what an open conversation uses")
    void anOpenConversationKeepsItsVersions() {
        prompts.save(PromptKind.BRIEF, "ORIGINAL BRIEF", "admin");
        UUID conversationId = openConversation("Unmoved");

        prompts.save(PromptKind.BRIEF, "REWRITTEN BRIEF", "admin");

        RecordingModel.systemPrompts.clear();
        chat.send(conversationId, "still there?");

        assertThat(RecordingModel.systemPrompts)
                .isNotEmpty()
                .allSatisfy(
                        prompt ->
                                assertThat(prompt).contains("ORIGINAL BRIEF").doesNotContain("REWRITTEN BRIEF"));
    }

    @Test
    @DisplayName("the next conversation gets the edit")
    void aNewConversationGetsTheNewVersion() {
        prompts.save(PromptKind.BRIEF, "BRIEF FOR THE NEXT ONE", "admin");

        RecordingModel.systemPrompts.clear();
        openConversation("Later");

        assertThat(RecordingModel.systemPrompts)
                .isNotEmpty()
                .allSatisfy(prompt -> assertThat(prompt).contains("BRIEF FOR THE NEXT ONE"));
    }

    @Test
    @DisplayName("a conversation from before pinning existed falls back to current, not to nothing")
    void unpinnedConversationsStillWork() {
        prompts.save(PromptKind.BRIEF, "FALLBACK BRIEF", "admin");
        UUID orphan = UUID.randomUUID();

        assertThat(prompts.usedBy(orphan)).isEmpty();
        assertThat(prompts.promptsFor(orphan).system()).contains("FALLBACK BRIEF");
    }

    @Test
    @DisplayName("what was pinned is what the review page will show")
    void pinnedVersionsAreReadableAfterwards() {
        Prompt briefAtTheTime = prompts.save(PromptKind.BRIEF, "AS IT WAS", "admin");
        UUID conversationId = openConversation("Readable");

        prompts.save(PromptKind.BRIEF, "AS IT IS NOW", "admin");

        assertThat(prompts.usedBy(conversationId))
                .filteredOn(prompt -> prompt.kind() == PromptKind.BRIEF)
                .singleElement()
                .satisfies(
                        used -> {
                            assertThat(used.version()).isEqualTo(briefAtTheTime.version());
                            assertThat(used.body()).isEqualTo("AS IT WAS");
                            // Superseded, which is exactly what the reviewer needs to be told.
                            assertThat(used.current()).isFalse();
                        });
    }
}
