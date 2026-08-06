package com.enrola;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One clock for the whole application, in the business timezone.
 *
 * <p>Shared rather than each part calling {@code Instant.now()}: the agent tells the model what
 * the time is so it can resolve "tomorrow arvo", and the diary decides whether the slot that
 * produces is still bookable. Those two disagreeing about now is a class of bug worth removing
 * outright, and a single injected clock is also what lets tests fix the date.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock(@Value("${booking.timezone}") ZoneId timezone) {
        return Clock.system(timezone);
    }
}
