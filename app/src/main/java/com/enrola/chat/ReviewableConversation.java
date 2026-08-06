package com.enrola.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin listing: enough to decide which conversation to read next, without
 * loading any of them.
 *
 * <p>{@code rating} is null when nobody has reviewed it yet, which is also what the unreviewed
 * filter selects on.
 */
public record ReviewableConversation(
        UUID id,
        String leadName,
        String title,
        int messageCount,
        String closedReason,
        Instant lastUsedAt,
        Integer rating,
        String comment) {

    public boolean reviewed() {
        return rating != null;
    }
}
