package com.enrola.chat;

import com.enrola.agent.BookingOutcome;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** The diary, kept in one table. {@link SlotRules} says what is valid; this says what is free. */
@Component
class PostgresDiary implements Diary {

    private static final Logger log = LoggerFactory.getLogger(PostgresDiary.class);

    /**
     * How many slots to consider when choosing alternatives -- about ten working days, which is
     * most of the booking horizon. Cheap enough to walk in memory off one range query.
     */
    private static final int CANDIDATES = 400;

    /**
     * How far back from the requested time to start looking. Alternatives should be able to land
     * earlier than what they asked for: if two o'clock has gone, quarter to is as good an answer
     * as quarter past.
     */
    private static final Duration LOOK_BACK = Duration.ofDays(1);

    private final BookingRepository bookings;
    private final SlotRules rules;
    private final int alternatives;

    PostgresDiary(
            BookingRepository bookings,
            SlotRules rules,
            @Value("${booking.alternatives}") int alternatives) {
        this.bookings = bookings;
        this.rules = rules;
        this.alternatives = alternatives;
    }

    @Override
    public BookingOutcome book(
            UUID conversationId, UUID leadId, Instant startsAt, String words, String topic) {

        String unavailable = rules.reasonUnavailable(startsAt).orElse(null);
        if (unavailable != null) {
            return offer(unavailable, startsAt);
        }
        if (bookings.isTaken(startsAt)) {
            return offer("that one is taken", startsAt);
        }

        try {
            bookings.insert(conversationId, leadId, startsAt, words, topic);
        } catch (DuplicateKeyException taken) {
            // Someone else booked it between the check above and this insert. The database is
            // the arbiter, and from this side a race and a collision are the same answer.
            log.info("Slot {} was taken during booking for conversation {}.", startsAt, conversationId);
            return offer("that one is taken", startsAt);
        }

        log.info("Booked {} for conversation {}.", startsAt, conversationId);
        return new BookingOutcome.Booked(rules.local(startsAt));
    }

    @Override
    public BookingOutcome.Unavailable nextAvailable(int count) {
        return new BookingOutcome.Unavailable("", free(count, null));
    }

    private BookingOutcome.Unavailable offer(String because, Instant wanted) {
        return new BookingOutcome.Unavailable(because, free(alternatives, wanted));
    }

    /**
     * Free slots: nearest to {@code wanted} when there is one, soonest otherwise. Returned in
     * time order either way, because reading three times out of order at someone is unkind.
     */
    private List<ZonedDateTime> free(int count, Instant wanted) {
        Instant earliest = rules.earliest();
        Instant from =
                wanted == null || wanted.minus(LOOK_BACK).isBefore(earliest)
                        ? earliest
                        : wanted.minus(LOOK_BACK);

        List<Instant> candidates = rules.slotsFrom(from, CANDIDATES);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<Instant> taken =
                new HashSet<>(
                        bookings.takenBetween(candidates.get(0), candidates.get(candidates.size() - 1)));

        return candidates.stream()
                .filter(slot -> !taken.contains(slot))
                .sorted(
                        wanted == null
                                ? Comparator.naturalOrder()
                                : Comparator.comparingLong(
                                        slot -> Math.abs(slot.getEpochSecond() - wanted.getEpochSecond())))
                .limit(count)
                .sorted()
                .map(rules::local)
                .toList();
    }
}
