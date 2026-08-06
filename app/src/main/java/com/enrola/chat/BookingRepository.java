package com.enrola.chat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Booked slots. One consultant, so a row here means that quarter hour is gone. */
@Repository
class BookingRepository {

    private final JdbcClient db;

    BookingRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Throws {@link org.springframework.dao.DuplicateKeyException} when the slot has just been
     * taken by someone else. The caller turns that into the same answer as a slot that was
     * already taken when it looked -- from the person's side the two are the same thing.
     */
    UUID insert(UUID conversationId, UUID leadId, Instant startsAt, String words, String topic) {
        UUID id = UUID.randomUUID();
        db.sql(
                        """
                        insert into booking (id, conversation_id, lead_id, starts_at, requested_words, topic)
                        values (:id, :conversationId, :leadId, :startsAt, :words, :topic)
                        """)
                .param("id", id)
                .param("conversationId", conversationId)
                .param("leadId", leadId)
                // PgJDBC cannot infer a SQL type for Instant; it takes OffsetDateTime.
                .param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
                .param("words", words)
                .param("topic", topic)
                .update();
        return id;
    }

    /** Slots already gone in the window, so availability is one query rather than one per slot. */
    List<Instant> takenBetween(Instant from, Instant to) {
        return db
                .sql("select starts_at from booking where starts_at >= :from and starts_at <= :to")
                .param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC))
                .query(OffsetDateTime.class)
                .list()
                .stream()
                .map(OffsetDateTime::toInstant)
                .toList();
    }

    Optional<Instant> findByConversation(UUID conversationId) {
        return db.sql("select starts_at from booking where conversation_id = :id")
                .param("id", conversationId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    boolean isTaken(Instant startsAt) {
        return db.sql("select count(*) from booking where starts_at = :startsAt")
                        .param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
                        .query(Integer.class)
                        .single()
                > 0;
    }
}
