package com.enrola.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * One version of one instruction.
 *
 * <p>{@code current} means this is the version a new conversation would be given. Exactly one
 * version of each kind has it, which the database enforces with a partial unique index rather
 * than leaving to whoever writes next.
 *
 * @param createdBy the admin who saved it, or null on the version seeded from the repo
 */
public record Prompt(
        UUID id,
        PromptKind kind,
        int version,
        String body,
        boolean current,
        Instant createdAt,
        String createdBy) {

    /** "Agent brief v2", for a page or a log line. */
    public String label() {
        return kind.displayName() + " v" + version;
    }
}
