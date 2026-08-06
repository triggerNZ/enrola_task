package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationMetricsTest {

    @Test
    void aSuccessfulCallbackResultIsABooking() {
        ConversationMetrics metrics =
                ConversationMetrics.from(
                        List.of(
                                toolResult(
                                        "arrange_callback",
                                        "Booked: Thu 6 Aug 2:15pm AEST. Confirm it back to them.")));

        assertThat(metrics.calendarBooked()).isTrue();
    }

    @Test
    void offersAndRefusalsAreNotBookings() {
        for (String result :
                List.of(
                        "No time given yet. Free: Thu 6 Aug 2:15pm AEST.",
                        "Not booked - that slot is taken. Free: Thu 6 Aug 2:30pm AEST.")) {
            assertThat(ConversationMetrics.from(List.of(toolResult("arrange_callback", result)))
                            .calendarBooked())
                    .as(result)
                    .isFalse();
        }
    }

    @Test
    void callingTheToolWithoutASuccessfulResultIsNotABooking() {
        MessageView call =
                new MessageView(
                        "AI",
                        null,
                        List.of(new MessageView.ToolCall("arrange_callback", List.of())),
                        null);

        assertThat(ConversationMetrics.from(List.of(call)).calendarBooked()).isFalse();
    }

    @Test
    void everyRecognisedOptOutCountsAsStop() {
        for (String text : List.of("STOP", " Stop. ", "UNSUBSCRIBE", "opt out")) {
            assertThat(ConversationMetrics.from(List.of(new MessageView("USER", text))).stopped())
                    .as(text)
                    .isTrue();
        }
    }

    @Test
    void unrelatedWordsAndAgentMessagesDoNotCountAsStop() {
        assertThat(
                        ConversationMetrics.from(
                                        List.of(
                                                new MessageView("USER", "please stop calling tomorrow"),
                                                new MessageView("AI", "STOP")))
                                .stopped())
                .isFalse();
    }

    @Test
    void bookingAndStopAreIndependentOutcomes() {
        ConversationMetrics metrics =
                ConversationMetrics.from(
                        List.of(
                                toolResult("arrange_callback", "Booked: Thu 6 Aug 2:15pm AEST."),
                                new MessageView("USER", "STOP")));

        assertThat(metrics.calendarBooked()).isTrue();
        assertThat(metrics.stopped()).isTrue();
    }

    private static MessageView toolResult(String toolName, String text) {
        return new MessageView("TOOL_EXECUTION_RESULT", text, List.of(), toolName);
    }
}
