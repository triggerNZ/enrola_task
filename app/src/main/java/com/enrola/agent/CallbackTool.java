package com.enrola.agent;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What happens when the agent decides someone is ready to speak to a consultant.
 *
 * <p>An interface because arranging the callback is not the agent's job -- it belongs to whoever
 * owns the diary and the conversation records. Declaring it here keeps this package free of
 * dependencies on the rest of the application while still letting the model reach out of it.
 */
public interface CallbackTool {

    /**
     * Books {@code startsAt} if it can be had. A refusal is a normal answer, not an error: the
     * conversation carries on from it so they can pick one of the alternatives.
     *
     * @param memoryId the conversation the request came from
     * @param startsAt the slot the model resolved from what they said
     * @param theirWords what they actually asked for -- "tomorrow arvo" -- kept beside the
     *     resolved time so a human can see whether it was read the way they meant it
     * @param topic what they want to discuss, or null
     */
    BookingOutcome arrangeCallback(Object memoryId, Instant startsAt, String theirWords, String topic);

    /** The next free slots on or after a date, or from now when {@code from} is null. */
    BookingOutcome.Unavailable nextAvailable(LocalDate from, int count);

    /** The next free slots from now, for offering times before anyone has named a day. */
    default BookingOutcome.Unavailable nextAvailable(int count) {
        return nextAvailable(null, count);
    }
}
