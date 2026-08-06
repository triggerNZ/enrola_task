package com.enrola.chat;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * What makes a callback slot valid, and how to walk the grid of them.
 *
 * <p>No database and no Spring beyond reading its settings, because this is where every awkward
 * case lives: the last slot of the day, a Friday afternoon request rolling to Monday, and the
 * two days a year the clocks move. All of it is arithmetic on {@link ZonedDateTime} in the
 * business zone, which is the only way those come out right.
 */
@Component
public class SlotRules {

    private final Clock clock;
    private final ZoneId zone;
    private final LocalTime opens;
    private final LocalTime closes;
    private final Duration slot;
    private final Duration minimumNotice;
    private final Duration horizon;

    SlotRules(
            Clock clock,
            @Value("${booking.timezone}") ZoneId zone,
            @Value("${booking.opens}") LocalTime opens,
            @Value("${booking.closes}") LocalTime closes,
            @Value("${booking.slot-minutes}") int slotMinutes,
            @Value("${booking.minimum-notice}") Duration minimumNotice,
            @Value("${booking.horizon}") Duration horizon) {
        if (slotMinutes < 1 || 60 % slotMinutes != 0) {
            // A slot that does not divide the hour puts the grid somewhere different every
            // hour, and the database CHECK that pins it to the quarter hour would reject it.
            throw new IllegalStateException(
                    "booking.slot-minutes must divide 60, got " + slotMinutes);
        }
        if (!opens.isBefore(closes)) {
            throw new IllegalStateException("booking.opens must be before booking.closes.");
        }
        this.clock = clock;
        this.zone = zone;
        this.opens = opens;
        this.closes = closes;
        this.slot = Duration.ofMinutes(slotMinutes);
        this.minimumNotice = minimumNotice;
        this.horizon = horizon;
    }

    public ZoneId zone() {
        return zone;
    }

    public Duration slotLength() {
        return slot;
    }

    /** The instant as the consultant would read it off their own wall. */
    public ZonedDateTime local(Instant instant) {
        return instant.atZone(zone);
    }

    /**
     * The time the model asked for, if it is a time at all. Accepts what the tool asks it to
     * send -- {@code 2026-08-07T14:15} in the business zone -- and tolerates a trailing
     * {@code :00} or a zone the model added unbidden.
     */
    public Optional<Instant> parse(String isoLocalDateTime) {
        if (!StringUtils.hasText(isoLocalDateTime)) {
            return Optional.empty();
        }
        String text = isoLocalDateTime.strip().replace(' ', 'T');
        try {
            // A model that volunteers an offset means it, so honour it rather than reading the
            // wall time as ours and moving the appointment by hours.
            return Optional.of(ZonedDateTime.parse(text).toInstant());
        } catch (DateTimeParseException notZoned) {
            try {
                return Optional.of(LocalDateTime.parse(text).atZone(zone).toInstant());
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
    }

    /** Why this instant cannot be booked, or empty when it can. */
    public Optional<String> reasonUnavailable(Instant when) {
        ZonedDateTime local = local(when);
        Instant now = clock.instant();

        if (!isAligned(local)) {
            return Optional.of("calls start on the quarter hour");
        }
        if (isWeekend(local)) {
            return Optional.of("we only take calls on weekdays");
        }
        if (!isWithinHours(local)) {
            return Optional.of("we take calls between %s and %s".formatted(hour(opens), hour(closes)));
        }
        if (when.isBefore(now.plus(minimumNotice))) {
            return Optional.of("that is too soon, we need %d minutes' notice".formatted(minimumNotice.toMinutes()));
        }
        if (when.isAfter(now.plus(horizon))) {
            return Optional.of("we only book %d days ahead".formatted(horizon.toDays()));
        }
        return Optional.empty();
    }

    public boolean isBookable(Instant when) {
        return reasonUnavailable(when).isEmpty();
    }

    /** The first slot that could be booked at all: now, plus notice, rounded up onto the grid. */
    public Instant earliest() {
        return nextOnGrid(clock.instant().plus(minimumNotice));
    }

    public Instant latest() {
        return clock.instant().plus(horizon);
    }

    /**
     * Every bookable slot from {@code from} onwards, up to {@code limit} of them. Walks day by
     * day in the business zone rather than adding fixed durations, so the day either side of a
     * daylight-saving change still opens at eight and closes at six.
     */
    public List<Instant> slotsFrom(Instant from, int limit) {
        List<Instant> found = new ArrayList<>();
        Instant floor = earliest();
        Instant ceiling = latest();
        LocalDate day = local(from.isBefore(floor) ? floor : from).toLocalDate();
        LocalDate lastDay = local(ceiling).toLocalDate();

        while (!day.isAfter(lastDay) && found.size() < limit) {
            for (Instant candidate : slotsOn(day)) {
                if (candidate.isBefore(from) || candidate.isBefore(floor) || candidate.isAfter(ceiling)) {
                    continue;
                }
                found.add(candidate);
                if (found.size() == limit) {
                    break;
                }
            }
            day = day.plusDays(1);
        }
        return List.copyOf(found);
    }

    /** Every slot on one day, earliest first. Empty at the weekend. */
    public List<Instant> slotsOn(LocalDate day) {
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return List.of();
        }
        List<Instant> slots = new ArrayList<>();
        LocalTime at = opens;
        // A slot has to finish by closing time, so with 15-minute slots and a six o'clock
        // close the last one starts at 17:45.
        while (!at.plus(slot).isAfter(closes)) {
            slots.add(day.atTime(at).atZone(zone).toInstant());
            LocalTime next = at.plus(slot);
            if (!next.isAfter(at)) {
                break; // wrapped past midnight; only reachable from an absurd configuration
            }
            at = next;
        }
        return List.copyOf(slots);
    }

    private boolean isAligned(ZonedDateTime local) {
        return local.getSecond() == 0
                && local.getNano() == 0
                && local.getMinute() % slot.toMinutes() == 0;
    }

    private static boolean isWeekend(ZonedDateTime local) {
        DayOfWeek day = local.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private boolean isWithinHours(ZonedDateTime local) {
        LocalTime at = local.toLocalTime();
        return !at.isBefore(opens) && !at.plus(slot).isAfter(closes);
    }

    /** Rounds forward onto the next slot boundary, leaving one already on it alone. */
    private Instant nextOnGrid(Instant instant) {
        long step = slot.toSeconds();
        long seconds = instant.getEpochSecond();
        long remainder = Math.floorMod(seconds, step);
        return remainder == 0
                ? Instant.ofEpochSecond(seconds)
                : Instant.ofEpochSecond(seconds + (step - remainder));
    }

    /** "8am", "6pm" -- for saying the hours out loud, not for parsing. */
    private static String hour(LocalTime time) {
        int hour = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        String suffix = time.getHour() < 12 ? "am" : "pm";
        return time.getMinute() == 0
                ? hour + suffix
                : "%d:%02d%s".formatted(hour, time.getMinute(), suffix);
    }
}
