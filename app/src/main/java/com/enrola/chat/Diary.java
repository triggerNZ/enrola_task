package com.enrola.chat;

import com.enrola.agent.BookingOutcome;
import java.time.Instant;
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

    /** The next free slots from now, for offering times before anyone has named one. */
    BookingOutcome.Unavailable nextAvailable(int count);
}
