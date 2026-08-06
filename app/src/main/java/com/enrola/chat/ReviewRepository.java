package com.enrola.chat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reviews, one per conversation. */
@Repository
class ReviewRepository {

    private final JdbcClient db;

    ReviewRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Writes the verdict, replacing any earlier one. An upsert rather than an insert so the
     * form is idempotent: submitting twice, or going back and saving again, changes the review
     * rather than failing on the primary key.
     */
    void save(UUID conversationId, int rating, String comment, String reviewer, Instant now) {
        db.sql(
                        """
                        insert into conversation_review (conversation_id, rating, comment, reviewer, updated_at)
                        values (:id, :rating, :comment, :reviewer, :now)
                        on conflict (conversation_id) do update
                           set rating = excluded.rating,
                               comment = excluded.comment,
                               reviewer = excluded.reviewer,
                               updated_at = excluded.updated_at
                        """)
                .param("id", conversationId)
                .param("rating", rating)
                .param("comment", comment)
                .param("reviewer", reviewer)
                // PgJDBC cannot infer a SQL type for Instant; it takes OffsetDateTime.
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    Optional<ConversationReview> find(UUID conversationId) {
        return db.sql(
                        """
                        select conversation_id, rating, comment, reviewer, updated_at
                          from conversation_review
                         where conversation_id = :id
                        """)
                .param("id", conversationId)
                .query(ConversationReview.class)
                .optional();
    }

    /** Used by tests to prove the upsert replaces rather than accumulates. */
    int count(UUID conversationId) {
        return db.sql("select count(*) from conversation_review where conversation_id = :id")
                .param("id", conversationId)
                .query(Integer.class)
                .single();
    }

    Optional<OffsetDateTime> createdAt(UUID conversationId) {
        return db.sql("select created_at from conversation_review where conversation_id = :id")
                .param("id", conversationId)
                .query(OffsetDateTime.class)
                .optional();
    }
}
