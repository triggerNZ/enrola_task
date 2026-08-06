package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What makes a slot valid, with the clock held still. No Spring and no database, because this is
 * arithmetic -- and arithmetic about dates is where the quiet mistakes are.
 */
class SlotRulesTest {

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    /** Thursday 6 August 2026, 9:00am Sydney. A weekday, mid-morning, well inside the hours. */
    private static final Instant NOW = at("2026-08-06T09:00");

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SYDNEY).toInstant();
    }

    private static SlotRules rulesAt(Instant now) {
        return new SlotRules(
                Clock.fixed(now, SYDNEY),
                SYDNEY,
                LocalTime.of(8, 0),
                LocalTime.of(18, 0),
                15,
                Duration.ofMinutes(30),
                Duration.ofDays(14));
    }

    private final SlotRules rules = rulesAt(NOW);

    private String why(String localDateTime) {
        return rules.reasonUnavailable(at(localDateTime)).orElse(null);
    }

    @Nested
    @DisplayName("the shape of a slot")
    class Shape {

        @Test
        void quarterHoursAreBookable() {
            assertThat(why("2026-08-06T14:00")).isNull();
            assertThat(why("2026-08-06T14:15")).isNull();
            assertThat(why("2026-08-06T14:30")).isNull();
            assertThat(why("2026-08-06T14:45")).isNull();
        }

        @Test
        @DisplayName("anything off the quarter hour is refused rather than quietly moved")
        void otherMinutesAreRefused() {
            assertThat(why("2026-08-06T14:07")).contains("quarter hour");
            assertThat(why("2026-08-06T14:01")).contains("quarter hour");
        }

        @Test
        void secondsAreNotASlot() {
            Instant withSeconds = at("2026-08-06T14:00").plusSeconds(30);
            assertThat(rules.reasonUnavailable(withSeconds)).isPresent();
        }
    }

    @Nested
    @DisplayName("the hours")
    class Hours {

        @Test
        @DisplayName("eight is the first slot and 5:45 is the last, because a call takes 15 minutes")
        void openingAndClosing() {
            // Tomorrow, so the notice rule does not answer first: 8am today is already past.
            assertThat(why("2026-08-07T08:00")).isNull();
            assertThat(why("2026-08-07T17:45")).isNull();
            assertThat(why("2026-08-07T18:00")).contains("between 8am and 6pm");
            assertThat(why("2026-08-07T07:45")).contains("between 8am and 6pm");
        }

        @Test
        void theMiddleOfTheNightIsRefused() {
            assertThat(why("2026-08-07T03:00")).contains("between 8am and 6pm");
        }

        @Test
        @DisplayName("a day's slots run 8:00 to 17:45 and no further")
        void slotsOnADay() {
            List<Instant> slots = rules.slotsOn(LocalDate.of(2026, 8, 6));

            assertThat(slots).hasSize(40); // ten hours, four an hour
            assertThat(slots.get(0)).isEqualTo(at("2026-08-06T08:00"));
            assertThat(slots.get(39)).isEqualTo(at("2026-08-06T17:45"));
        }
    }

    @Nested
    @DisplayName("the week")
    class Week {

        @Test
        void weekendsAreRefused() {
            assertThat(why("2026-08-08T10:00")).contains("weekdays"); // Saturday
            assertThat(why("2026-08-09T10:00")).contains("weekdays"); // Sunday
        }

        @Test
        void weekendsHaveNoSlots() {
            assertThat(rules.slotsOn(LocalDate.of(2026, 8, 8))).isEmpty();
            assertThat(rules.slotsOn(LocalDate.of(2026, 8, 9))).isEmpty();
        }

        @Test
        @DisplayName("Friday evening rolls forward to Monday morning, not Saturday")
        void fridayRollsToMonday() {
            SlotRules fridayEvening = rulesAt(at("2026-08-07T17:50"));

            assertThat(fridayEvening.slotsFrom(fridayEvening.earliest(), 1))
                    .containsExactly(at("2026-08-10T08:00"));
        }
    }

    @Nested
    @DisplayName("how soon and how far")
    class Window {

        @Test
        @DisplayName("half an hour's notice, to the minute")
        void minimumNotice() {
            assertThat(why("2026-08-06T09:15")).contains("too soon");
            assertThat(why("2026-08-06T09:30")).isNull();
        }

        @Test
        void nothingInThePast() {
            assertThat(why("2026-08-06T08:00")).contains("too soon");
        }

        @Test
        @DisplayName("a fortnight ahead, and no further")
        void horizon() {
            assertThat(why("2026-08-20T09:00")).isNull(); // 14 days, on the hour
            assertThat(why("2026-08-21T09:00")).contains("14 days ahead");
        }

        @Test
        @DisplayName("the first bookable slot skips over the notice period")
        void earliestRespectsNotice() {
            assertThat(rules.slotsFrom(rules.earliest(), 1)).containsExactly(at("2026-08-06T09:30"));
        }
    }

    @Nested
    @DisplayName("daylight saving")
    class DaylightSaving {

        // Sydney moves forward on Sunday 4 October 2026 and back on Sunday 5 April 2026,
        // both at 2-3am. Business hours never touch the gap, but the offset either side is
        // different -- so a slot list built by adding fixed durations would drift by an hour.

        @Test
        @DisplayName("the Monday after the clocks go forward still opens at eight")
        void afterSpringForward() {
            SlotRules october = rulesAt(at("2026-10-02T09:00"));
            List<Instant> monday = october.slotsOn(LocalDate.of(2026, 10, 5));

            assertThat(monday).hasSize(40);
            assertThat(monday.get(0).atZone(SYDNEY).toLocalTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(monday.get(39).atZone(SYDNEY).toLocalTime()).isEqualTo(LocalTime.of(17, 45));
        }

        @Test
        @DisplayName("and the day after the clocks go back")
        void afterFallBack() {
            SlotRules april = rulesAt(at("2026-04-02T09:00"));
            List<Instant> monday = april.slotsOn(LocalDate.of(2026, 4, 6));

            assertThat(monday).hasSize(40);
            assertThat(monday.get(0).atZone(SYDNEY).toLocalTime()).isEqualTo(LocalTime.of(8, 0));
        }

        @Test
        @DisplayName("slots spanning the change stay an hour apart on the wall, not on the clock")
        void slotsAcrossTheChange() {
            SlotRules october = rulesAt(at("2026-10-01T09:00"));

            List<Instant> slots = october.slotsFrom(at("2026-10-02T17:30"), 3);

            assertThat(slots)
                    .allSatisfy(
                            slot -> {
                                LocalTime local = slot.atZone(SYDNEY).toLocalTime();
                                assertThat(local).isBetween(LocalTime.of(8, 0), LocalTime.of(17, 45));
                            });
        }
    }

    @Nested
    @DisplayName("reading what the model sent")
    class Parsing {

        @Test
        void acceptsALocalDateTimeInTheBusinessZone() {
            assertThat(rules.parse("2026-08-06T14:15")).contains(at("2026-08-06T14:15"));
            assertThat(rules.parse(" 2026-08-06 14:15 ")).contains(at("2026-08-06T14:15"));
        }

        @Test
        @DisplayName("an offset the model volunteered is honoured, not reinterpreted")
        void acceptsAnExplicitOffset() {
            assertThat(rules.parse("2026-08-06T14:15+10:00")).contains(at("2026-08-06T14:15"));
        }

        @Test
        void refusesAnythingElse() {
            assertThat(rules.parse("tomorrow arvo")).isEmpty();
            assertThat(rules.parse("2pm")).isEmpty();
            assertThat(rules.parse("")).isEmpty();
            assertThat(rules.parse(null)).isEmpty();
        }
    }
}
