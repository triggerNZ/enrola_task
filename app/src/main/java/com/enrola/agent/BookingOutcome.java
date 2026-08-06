package com.enrola.agent;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * What came of trying to book a callback.
 *
 * <p>Times are already in the business timezone, so whatever formats them for the model is
 * formatting rather than converting -- there is one place that decides what zone the diary runs
 * in, and it is not here.
 */
public sealed interface BookingOutcome {

    /** The slot is theirs. */
    record Booked(ZonedDateTime at) implements BookingOutcome {}

    /**
     * It could not be had. One record covers every refusal -- taken, outside hours, a weekend,
     * too soon, too far off -- because the agent does the same thing with all of them: say why
     * in a few words and offer what is free instead.
     *
     * @param because a short phrase, lowercase, that reads inside a sentence
     * @param alternatives the nearest free slots, soonest first; may be empty
     */
    record Unavailable(String because, List<ZonedDateTime> alternatives) implements BookingOutcome {}
}
