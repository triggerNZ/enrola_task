package com.enrola.agent.tools;

import com.enrola.agent.BookingOutcome;
import com.enrola.agent.CallbackTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/**
 * Books a fifteen-minute slot in the consultant's diary, or says why it could not and offers
 * what is free instead.
 *
 * <p>Offering is the same tool as booking: called without a time it answers with the next free
 * slots. The agent needs that, because it has to put a real time in front of someone before they
 * can agree to one, and guessing blind would collide.
 */
@AgentTool
public class ArrangeCallbackTool {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private static final DateTimeFormatter ZONE = DateTimeFormatter.ofPattern("z", Locale.ENGLISH);

    private final CallbackTool callbacks;
    private final ZoneId zone;

    public ArrangeCallbackTool(CallbackTool callbacks, @Value("${booking.timezone}") ZoneId zone) {
        this.callbacks = callbacks;
        this.zone = zone;
    }

    @Tool(
            name = "arrange_callback",
            value =
                    "Book a 15-minute call with a consultant. Use when the person agrees to a call, asks"
                        + " what they personally should buy, or asks something you cannot answer from the"
                        + " FAQ or the catalogue. Call it with no time to be told what is free; use"
                        + " from_date when they named a day but not a time.")
    public String arrangeCallback(
            // Which conversation to close. langchain4j fills this in and leaves it out of the
            // schema, so the model never sees it and cannot get it wrong.
            @ToolMemoryId Object conversationId,
            // The schema name is pinned: langchain4j would otherwise take it from the Java
            // parameter, and renaming a field the model has been trained to send is not
            // something a variable rename should be able to do.
            @P(
                            name = "starts_at",
                            value =
                                    "The quarter-hour to book, as YYYY-MM-DDTHH:MM, in the timezone given"
                                        + " in your brief. Work it out from the current time you were"
                                        + " given. Leave it out to be told the next free times.",
                            required = false)
                    String startsAt,
            @P(
                            name = "from_date",
                            value =
                                    "The first day to offer when the person named a day but not a"
                                        + " time, as YYYY-MM-DD. Leave it out to offer from now.",
                            required = false)
                    String fromDate,
            @P(
                            name = "their_words",
                            value = "When they asked for, in their own words: 'tomorrow arvo'.",
                            required = false)
                    String theirWords,
            @P(value = "What they want to discuss, if they said.", required = false) String topic) {

        if (!StringUtils.hasText(startsAt)) {
            LocalDate from = parseDate(fromDate);
            if (StringUtils.hasText(fromDate) && from == null) {
                return "That date did not make sense. Ask which day suits.";
            }
            return offers(callbacks.nextAvailable(from, 3), "No exact time given yet.");
        }

        Instant wanted = parse(startsAt);
        if (wanted == null) {
            return offers(callbacks.nextAvailable(3), "That time did not make sense.");
        }

        return switch (callbacks.arrangeCallback(conversationId, wanted, theirWords, topic)) {
            case BookingOutcome.Booked booked ->
                    ("Booked: %s%s. Confirm it back to them in one short message, with the day,"
                                    + " and say goodbye.")
                            .formatted(slot(booked.at(), true), zoneName(booked.at()));
            case BookingOutcome.Unavailable no -> offers(no, "Not booked - " + no.because() + ".");
        };
    }

    /** The refusal or prompt, then what is actually free, in as few characters as it takes. */
    private String offers(BookingOutcome.Unavailable outcome, String lead) {
        List<ZonedDateTime> free = outcome.alternatives();
        if (free.isEmpty()) {
            return lead + " Nothing is free in the next fortnight. Apologise and offer to text them.";
        }
        return "%s Free: %s. Offer two of these and ask which suits."
                .formatted(lead, list(free));
    }

    /**
     * "Thu 7 Aug 2:15pm, 2:30pm, Fri 8 Aug 8am AEST" -- the date is repeated only when the day
     * changes, because these go into a text message.
     */
    private String list(List<ZonedDateTime> slots) {
        StringJoiner out = new StringJoiner(", ");
        ZonedDateTime previous = null;
        for (ZonedDateTime slot : slots) {
            boolean newDay = previous == null || !previous.toLocalDate().equals(slot.toLocalDate());
            out.add(slot(slot, newDay));
            previous = slot;
        }
        return out + zoneName(slots.get(slots.size() - 1));
    }

    /**
     * "Thu 7 Aug 2:15pm", or just "3pm" once the day is established. Built by hand rather than
     * with a pattern: the am/pm marker has to be lowercase while the month stays capitalised, and
     * an o'clock time should not read "3:00pm".
     */
    private static String slot(ZonedDateTime at, boolean withDay) {
        int hour = at.getHour() % 12 == 0 ? 12 : at.getHour() % 12;
        String meridiem = at.getHour() < 12 ? "am" : "pm";
        String clock =
                at.getMinute() == 0
                        ? hour + meridiem
                        : "%d:%02d%s".formatted(hour, at.getMinute(), meridiem);
        return withDay ? DAY.format(at) + " " + clock : clock;
    }

    private static String zoneName(ZonedDateTime at) {
        return " " + ZONE.format(at);
    }

    /** Null when the model sent something that is not a time. */
    private Instant parse(String text) {
        String cleaned = text.strip().replace(' ', 'T');
        try {
            return ZonedDateTime.parse(cleaned).toInstant();
        } catch (DateTimeParseException notZoned) {
            try {
                return LocalDateTime.parse(cleaned).atZone(zone).toInstant();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    /** Null when the model sent no date, or something other than an ISO calendar date. */
    private static LocalDate parseDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
