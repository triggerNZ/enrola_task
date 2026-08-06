package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.agent.BookingOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The diary against a real Postgres, with the clock held at a Thursday morning so the same
 * afternoon is bookable on every run. {@link SlotRulesTest} covers what makes a slot valid; this
 * covers what is free, and what the database refuses.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"openai.api-key=test-key", "admin.password=test"})
@Import(PostgresDiaryTest.FrozenClock.class)
class PostgresDiaryTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    @TestConfiguration
    static class FrozenClock {
        /**
         * Named differently from {@code TimeConfig.clock}: same-named beans are an override,
         * which Spring Boot refuses outright, and @Primary does not save you from that.
         */
        @Bean
        @Primary
        Clock frozenClock() {
            return Clock.fixed(at("2026-08-06T09:00"), SYDNEY);
        }
    }

    @Autowired private Diary diary;
    @Autowired private BookingRepository bookings;
    @Autowired private ConversationRepository conversations;
    @Autowired private JdbcClient db;

    @BeforeEach
    void emptyTheDiary() {
        // These assert exact times, and every test shares one container and one frozen clock.
        // A booking left behind by the previous test silently changes what "nearest free" means.
        db.sql("delete from booking").update();
    }

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SYDNEY).toInstant();
    }

    /** A conversation to hang bookings off; the FK is real. */
    private UUID conversation() {
        return conversations.create(null, "booking test");
    }

    private BookingOutcome book(String localDateTime) {
        return diary.book(conversation(), null, at(localDateTime), "tomorrow arvo", "price");
    }

    @Test
    @DisplayName("a free slot is taken, and is not free afterwards")
    void booksAndThenIsTaken() {
        BookingOutcome first = book("2026-08-06T10:00");

        assertThat(first)
                .isInstanceOfSatisfying(
                        BookingOutcome.Booked.class,
                        booked -> assertThat(booked.at().toInstant()).isEqualTo(at("2026-08-06T10:00")));
        assertThat(bookings.isTaken(at("2026-08-06T10:00"))).isTrue();

        assertThat(book("2026-08-06T10:00"))
                .isInstanceOfSatisfying(
                        BookingOutcome.Unavailable.class,
                        no -> assertThat(no.because()).contains("taken"));
    }

    @Test
    @DisplayName("what they said is kept beside the time it was read as")
    void recordsTheirWords() {
        UUID conversationId = conversation();
        diary.book(conversationId, null, at("2026-08-06T10:15"), "tomorrow arvo", "price");

        assertThat(bookings.findByConversation(conversationId)).contains(at("2026-08-06T10:15"));
    }

    @Test
    @DisplayName("a taken slot is answered with the nearest free ones, on both sides of it")
    void suggestsNearestAlternatives() {
        book("2026-08-06T14:00");

        BookingOutcome.Unavailable no = (BookingOutcome.Unavailable) book("2026-08-06T14:00");

        assertThat(no.alternatives()).hasSize(3).doesNotContain(at("2026-08-06T14:00").atZone(SYDNEY));
        // Nearest either way: quarter to is as good an answer as quarter past.
        assertThat(instants(no.alternatives()))
                .contains(at("2026-08-06T13:45"), at("2026-08-06T14:15"));
    }

    @Test
    @DisplayName("alternatives skip everything already booked, not just the one they asked for")
    void alternativesSkipTakenSlots() {
        book("2026-08-06T14:00");
        book("2026-08-06T13:45");
        book("2026-08-06T14:15");

        BookingOutcome.Unavailable no = (BookingOutcome.Unavailable) book("2026-08-06T14:00");

        assertThat(instants(no.alternatives()))
                .doesNotContain(at("2026-08-06T13:45"), at("2026-08-06T14:00"), at("2026-08-06T14:15"))
                .contains(at("2026-08-06T13:30"), at("2026-08-06T14:30"));
    }

    @Test
    @DisplayName("an out-of-hours request is refused with real times, not a shrug")
    void outOfHoursStillOffers() {
        BookingOutcome.Unavailable no = (BookingOutcome.Unavailable) book("2026-08-07T03:00");

        assertThat(no.because()).contains("8am and 6pm");
        assertThat(no.alternatives()).isNotEmpty();
        assertThat(instants(no.alternatives()))
                .allSatisfy(slot -> assertThat(slot.atZone(SYDNEY).getHour()).isBetween(8, 17));
    }

    @Test
    @DisplayName("a weekend request is offered weekdays")
    void weekendStillOffers() {
        BookingOutcome.Unavailable no = (BookingOutcome.Unavailable) book("2026-08-08T10:00");

        assertThat(no.because()).contains("weekdays");
        assertThat(instants(no.alternatives()))
                .allSatisfy(
                        slot ->
                                assertThat(slot.atZone(SYDNEY).getDayOfWeek().getValue()).isLessThanOrEqualTo(5));
    }

    @Test
    @DisplayName("offering times with nothing asked for gives the soonest, in order")
    void nextAvailableIsSoonestFirst() {
        BookingOutcome.Unavailable offers = diary.nextAvailable(3);

        assertThat(offers.alternatives()).hasSize(3);
        assertThat(instants(offers.alternatives())).isSorted();
        assertThat(instants(offers.alternatives()).get(0)).isAfterOrEqualTo(at("2026-08-06T09:30"));
    }

    @Test
    @DisplayName("availability can start from a given business date")
    void nextAvailableStartsFromDate() {
        BookingOutcome.Unavailable offers = diary.nextAvailable(LocalDate.parse("2026-08-10"), 3);

        assertThat(instants(offers.alternatives()))
                .containsExactly(
                        at("2026-08-10T08:00"),
                        at("2026-08-10T08:15"),
                        at("2026-08-10T08:30"));
    }

    @Test
    @DisplayName("the database refuses a slot off the quarter hour, whatever the code thinks")
    void alignmentIsEnforcedBelowTheService() {
        assertThatThrownBy(
                        () ->
                                bookings.insert(
                                        conversation(), null, at("2026-08-06T14:07"), "seven past", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two bookings for one slot is refused by the database, not only by the check")
    void oneSlotOneBooking() {
        bookings.insert(conversation(), null, at("2026-08-06T16:00"), null, null);

        assertThatThrownBy(
                        () -> bookings.insert(conversation(), null, at("2026-08-06T16:00"), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static List<Instant> instants(List<ZonedDateTime> slots) {
        return slots.stream().map(ZonedDateTime::toInstant).toList();
    }
}
