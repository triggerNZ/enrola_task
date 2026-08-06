package com.enrola.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * A conversation without its messages: what the API returns when listing or looking one up,
 * and what {@code chat.sh} reads the restored message count from.
 *
 * <p>{@code title} is null until the first message names the conversation. {@code messageCount}
 * is the visible transcript length, so it respects the clear watermark. A non-null
 * {@code closedAt} means the agent has stopped answering -- {@code closedReason} says why, and
 * {@code closingNote} holds what they asked for, in their words.
 */
public record ConversationSummary(
        UUID id,
        UUID leadId,
        String title,
        int messageCount,
        Instant closedAt,
        String closedReason,
        String closingNote) {

    /** Why a conversation ended. */
    public static final String CALLBACK = "callback";

    public static final String OPTED_OUT = "opted_out";

    static ConversationSummary opened(UUID id, UUID leadId) {
        return new ConversationSummary(id, leadId, null, 0, null, null, null);
    }

    public boolean closed() {
        return closedAt != null;
    }
}
