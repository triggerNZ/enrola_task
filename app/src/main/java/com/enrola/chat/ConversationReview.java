package com.enrola.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * What an admin thought of a conversation: a mark out of five and what to do better.
 *
 * <p>One per conversation. Reviewing it again rewrites this rather than adding to it, because
 * the useful question is what someone thinks of it now.
 */
public record ConversationReview(
        UUID conversationId, int rating, String comment, String reviewer, Instant updatedAt) {

    public static final int LOWEST = 1;
    public static final int HIGHEST = 5;
}
