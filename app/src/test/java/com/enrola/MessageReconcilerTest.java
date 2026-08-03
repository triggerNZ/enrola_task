package com.enrola;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * No Spring, no database. Every branch of the reconciliation algorithm is covered here so
 * the subtle cases are cheap to run and cheap to re-check.
 */
class MessageReconcilerTest {

    private static ChatMessage u(String text) {
        return UserMessage.from(text);
    }

    private static ChatMessage a(String text) {
        return AiMessage.from(text);
    }

    private static ChatMessage s(String text) {
        return SystemMessage.from(text);
    }

    @Nested
    @DisplayName("trivial cases")
    class Trivial {

        @Test
        void emptyWindowAppendsNothing() {
            assertThat(MessageReconciler.newMessages(List.of(u("a")), List.of())).isEmpty();
        }

        @Test
        void emptyTranscriptAppendsEverything() {
            List<ChatMessage> window = List.of(u("hello"));
            assertThat(MessageReconciler.newMessages(List.of(), window)).containsExactlyElementsOf(window);
        }

        @Test
        void emptyTranscriptWithSystemPromptAppendsEverything() {
            List<ChatMessage> window = List.of(s("be terse"), u("hello"));
            assertThat(MessageReconciler.newMessages(List.of(), window)).containsExactlyElementsOf(window);
        }
    }

    @Nested
    @DisplayName("contiguous windows (step 1)")
    class Contiguous {

        @Test
        void appendsOnlyTheNewMessage() {
            List<ChatMessage> stored = List.of(u("q1"), a("r1"));
            List<ChatMessage> window = List.of(u("q1"), a("r1"), u("q2"));
            assertThat(MessageReconciler.newMessages(stored, window)).containsExactly(u("q2"));
        }

        @Test
        void appendsNewMessageWhenWindowHasSlidPastOlderTurns() {
            List<ChatMessage> stored = List.of(u("q1"), a("r1"), u("q2"), a("r2"));
            List<ChatMessage> window = List.of(u("q2"), a("r2"), u("q3"));
            assertThat(MessageReconciler.newMessages(stored, window)).containsExactly(u("q3"));
        }

        @Test
        @DisplayName("a pure trim appends nothing")
        void windowFullyContainedAppendsNothing() {
            List<ChatMessage> stored = List.of(u("q1"), a("r1"), u("q2"), a("r2"));
            List<ChatMessage> window = List.of(u("q2"), a("r2"));
            assertThat(MessageReconciler.newMessages(stored, window)).isEmpty();
        }

        @Test
        void identicalWindowAppendsNothing() {
            List<ChatMessage> stored = List.of(u("q1"), a("r1"));
            assertThat(MessageReconciler.newMessages(stored, stored)).isEmpty();
        }

        @Test
        @DisplayName("repeated identical messages: the case that pins step 1 ahead of step 2")
        void repeatedIdenticalMessagesStillDetectTheNewOne() {
            // The user has said "hi" twice already and is saying it a third time. Subsequence
            // matching alone would match all three against the transcript's front and conclude
            // nothing is new, silently losing the message.
            List<ChatMessage> stored = List.of(u("hi"), a("x"), u("hi"), a("x"));
            List<ChatMessage> window = List.of(u("hi"), a("x"), u("hi"));

            assertThat(MessageReconciler.newMessages(stored, window)).containsExactly(u("hi"));
        }
    }

    @Nested
    @DisplayName("system messages (step 2)")
    class SystemMessages {

        @Test
        @DisplayName("pinned at the front while older turns are evicted")
        void pinnedSystemMessageIsNotTreatedAsNew() {
            // A SystemMessage is never evicted, so the window is a subsequence of the
            // transcript rather than a suffix of it.
            List<ChatMessage> stored = List.of(s("be terse"), u("q1"), a("r1"), u("q2"), a("r2"));
            List<ChatMessage> window = List.of(s("be terse"), u("q2"), a("r2"), u("q3"));

            assertThat(MessageReconciler.newMessages(stored, window)).containsExactly(u("q3"));
        }

        @Test
        @DisplayName("replaced and relocated to the end")
        void replacedSystemMessageIsAppendedOnce() {
            // MessageWindowChatMemory removes the old SystemMessage and appends the new one at
            // the end (unless alwaysKeepSystemMessageFirst is set).
            List<ChatMessage> stored = List.of(s("old"), u("q1"), a("r1"));
            List<ChatMessage> window = List.of(u("q1"), a("r1"), s("new"));

            assertThat(MessageReconciler.newMessages(stored, window)).containsExactly(s("new"));
        }

        @Test
        void replacedSystemMessageFromMidTranscript() {
            List<ChatMessage> stored = List.of(u("q1"), a("r1"), s("old"), u("q2"), a("r2"));
            List<ChatMessage> window = List.of(u("q1"), a("r1"), u("q2"), a("r2"), s("new"));

            assertThat(MessageReconciler.newMessages(stored, window)).containsExactly(s("new"));
        }
    }

    @Nested
    @DisplayName("tool calls")
    class ToolCalls {

        private static AiMessage aiWithToolRequest() {
            return AiMessage.from(
                    ToolExecutionRequest.builder().id("call-1").name("lookup").arguments("{}").build());
        }

        @Test
        @DisplayName("orphan tool results evicted alongside their AiMessage")
        void toolOrphanEvictionStaysContiguous() {
            // Evicting the AiMessage cascades to the following ToolExecutionResultMessage, but
            // both leave from the front, so the window is still a suffix of the transcript.
            List<ChatMessage> stored =
                    List.of(
                            u("q1"),
                            aiWithToolRequest(),
                            ToolExecutionResultMessage.from("call-1", "lookup", "42"),
                            a("r1"),
                            u("q2"));
            List<ChatMessage> window = List.of(a("r1"), u("q2"), a("r2"));

            assertThat(MessageReconciler.newMessages(stored, window)).containsExactly(a("r2"));
        }

        @Test
        void toolMessagesRoundTripThroughEquality() {
            List<ChatMessage> stored = List.of(u("q1"));
            List<ChatMessage> window =
                    List.of(
                            u("q1"),
                            aiWithToolRequest(),
                            ToolExecutionResultMessage.from("call-1", "lookup", "42"));

            assertThat(MessageReconciler.newMessages(stored, window))
                    .containsExactly(
                            aiWithToolRequest(),
                            ToolExecutionResultMessage.from("call-1", "lookup", "42"));
        }
    }

    @Nested
    @DisplayName("set() and unrelated windows (step 3)")
    class Unrelated {

        @Test
        @DisplayName("a shorter prefix appends nothing -- removal is structurally impossible")
        void shorterPrefixAppendsNothing() {
            // Documents the limitation: an append-only store cannot un-add a message, so
            // set(listWithoutLast) is a no-op rather than a removal.
            List<ChatMessage> stored = List.of(u("q1"), a("r1"), u("q2"));
            List<ChatMessage> window = List.of(u("q1"), a("r1"));

            assertThat(MessageReconciler.newMessages(stored, window)).isEmpty();
        }

        @Test
        void completelyUnrelatedWindowIsAppendedWholesale() {
            List<ChatMessage> stored = List.of(u("q1"), a("r1"));
            List<ChatMessage> window = List.of(u("x1"), a("x2"));

            assertThat(MessageReconciler.newMessages(stored, window)).containsExactlyElementsOf(window);
        }

        @Test
        void noOverlapAtAllIsAppended() {
            assertThat(MessageReconciler.newMessages(List.of(u("q1"), a("r1")), List.of(u("q2"))))
                    .containsExactly(u("q2"));
        }
    }
}
