package com.enrola.chat;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Rating conversations and saying what should have gone better.
 *
 * <p>Used by both the admin pages and the API, so the rules cannot come apart between them. The
 * database constrains the rating too; this exists to fail with something a person can read
 * rather than a constraint violation.
 */
@Service
public class ReviewService {

    /** Long enough for a paragraph of notes, short enough that the column is not a dumping ground. */
    private static final int COMMENT_MAX_LENGTH = 2000;

    private final ReviewRepository reviews;
    private final ConversationRepository conversations;
    private final Clock clock;

    ReviewService(ReviewRepository reviews, ConversationRepository conversations, Clock clock) {
        this.reviews = reviews;
        this.conversations = conversations;
        this.clock = clock;
    }

    /** Conversations worth reading, newest first. */
    public List<ReviewableConversation> awaitingReview(boolean unreviewedOnly, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        return conversations.forReview(unreviewedOnly, limit);
    }

    public Optional<ConversationReview> find(UUID conversationId) {
        return reviews.find(conversationId);
    }

    /**
     * Records the verdict, replacing any earlier one.
     *
     * @param comment what to do better; may be blank, because a rating on its own is a real
     *     opinion and demanding prose would just produce prose nobody means
     */
    public ConversationReview save(UUID conversationId, int rating, String comment, String reviewer) {
        if (rating < ConversationReview.LOWEST || rating > ConversationReview.HIGHEST) {
            throw new IllegalArgumentException(
                    "Rating must be between %d and %d, got %d"
                            .formatted(ConversationReview.LOWEST, ConversationReview.HIGHEST, rating));
        }
        String trimmed = StringUtils.hasText(comment) ? comment.strip() : null;
        if (trimmed != null && trimmed.length() > COMMENT_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Comment must be under %d characters.".formatted(COMMENT_MAX_LENGTH));
        }
        if (!conversations.exists(conversationId)) {
            throw new UnknownConversationException(conversationId);
        }

        reviews.save(conversationId, rating, trimmed, reviewer, clock.instant());
        return reviews.find(conversationId).orElseThrow();
    }
}
