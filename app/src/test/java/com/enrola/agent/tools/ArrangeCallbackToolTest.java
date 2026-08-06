package com.enrola.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.agent.BookingOutcome;
import com.enrola.agent.CallbackTool;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The words the model gets back. These become a message in the transcript and are paraphrased
 * into a text, so they are asserted for what they say and for being short enough to say it.
 */
class ArrangeCallbackToolTest {

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private final UUID conversationId = UUID.randomUUID();

    private static ZonedDateTime at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SYDNEY);
    }

    /** Answers with whatever it was constructed with, so the wording is what is under test. */
    private ArrangeCallbackTool toolReturning(BookingOutcome outcome) {
        return new ArrangeCallbackTool(
                new CallbackTool() {
                    @Override
                    public BookingOutcome arrangeCallback(
                            Object memoryId, Instant startsAt, String theirWords, String topic) {
                        return outcome;
                    }

                    @Override
                    public BookingOutcome.Unavailable nextAvailable(int count) {
                        return new BookingOutcome.Unavailable(
                                "", List.of(at("2026-08-06T09:30"), at("2026-08-06T09:45")));
                    }
                },
                SYDNEY);
    }

    @Test
    @DisplayName("a booking comes back with the day, the time and the zone")
    void bookedNamesTheWholeThing() {
        String result =
                toolReturning(new BookingOutcome.Booked(at("2026-08-06T14:15")))
                        .arrangeCallback(conversationId, "2026-08-06T14:15", "tomorrow arvo", "price");

        assertThat(result).startsWith("Booked: Thu 6 Aug 2:15pm AEST");
        assertThat(result).contains("Confirm it back");
    }

    @Test
    @DisplayName("an o'clock time does not read as 3:00pm")
    void wholeHoursAreSaidPlainly() {
        String result =
                toolReturning(new BookingOutcome.Booked(at("2026-08-06T15:00")))
                        .arrangeCallback(conversationId, "2026-08-06T15:00", null, null);

        assertThat(result).contains("3pm").doesNotContain("3:00");
    }

    @Test
    @DisplayName("a refusal says why, then what is free")
    void refusalCarriesTheReasonAndTheAlternatives() {
        String result =
                toolReturning(
                                new BookingOutcome.Unavailable(
                                        "that one is taken",
                                        List.of(at("2026-08-06T13:45"), at("2026-08-06T14:30"))))
                        .arrangeCallback(conversationId, "2026-08-06T14:00", null, null);

        assertThat(result)
                .startsWith("Not booked - that one is taken.")
                .contains("Thu 6 Aug 1:45pm")
                .contains("2:30pm")
                .contains("Offer two");
    }

    @Test
    @DisplayName("the day is named once, not on every time in the list")
    void theDayIsNotRepeated() {
        String result =
                toolReturning(
                                new BookingOutcome.Unavailable(
                                        "that one is taken",
                                        List.of(
                                                at("2026-08-06T13:45"),
                                                at("2026-08-06T14:30"),
                                                at("2026-08-07T08:00"))))
                        .arrangeCallback(conversationId, "2026-08-06T14:00", null, null);

        assertThat(result).containsOnlyOnce("Thu 6 Aug").contains("Fri 7 Aug 8am");
    }

    @Test
    @DisplayName("with no time given it offers what is free instead of failing")
    void noTimeAsksTheDiary() {
        String result =
                toolReturning(new BookingOutcome.Booked(at("2026-08-06T14:15")))
                        .arrangeCallback(conversationId, null, null, null);

        assertThat(result).startsWith("No time given yet.").contains("9:30am").contains("9:45am");
    }

    @Test
    @DisplayName("a time that is not a time asks again rather than throwing")
    void nonsenseTimeIsHandled() {
        String result =
                toolReturning(new BookingOutcome.Booked(at("2026-08-06T14:15")))
                        .arrangeCallback(conversationId, "tomorrow arvo", null, null);

        assertThat(result).startsWith("That time did not make sense.").contains("9:30am");
    }

    @Test
    @DisplayName("nothing free at all is admitted, not papered over")
    void anEmptyDiaryIsSaidPlainly() {
        String result =
                toolReturning(new BookingOutcome.Unavailable("that one is taken", List.of()))
                        .arrangeCallback(conversationId, "2026-08-06T14:00", null, null);

        assertThat(result).contains("Nothing is free").contains("Apologise");
    }
}
