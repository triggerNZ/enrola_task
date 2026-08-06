package com.enrola.chat;

import com.enrola.agent.BookingOutcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The consultant's calendar.
 *
 * <p>An interface with one implementation today, because this is the seam a real scheduling
 * provider arrives through. Everything above it -- the tool, the agent, the brief -- deals in
 * "booked" or "here are other times", which is true whether the slots live in Postgres or in
 * someone else's API.
 */
public interface Diary {

    /**
     * Takes the slot if it can be had, and otherwise says why and offers the nearest free ones.
     * Never throws for an unbookable time: a refusal is a normal answer here, because the
     * conversation carries on from it.
     */
    BookingOutcome book(UUID conversationId, UUID leadId, Instant startsAt, String words, String topic);

    /**
     * The next free slots on or after {@code from}, in the diary's business timezone. A null date
     * starts from the earliest currently bookable slot.
     */
    BookingOutcome.Unavailable nextAvailable(LocalDate from, int count);

    /** The next free slots from now, for offering times before anyone has named a day. */
    default BookingOutcome.Unavailable nextAvailable(int count) {
        return nextAvailable(null, count);
    }
}
