package com.enrola.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Conversation rows: creating them, finding one to resume, and keeping them tidy. */
@Repository
class ConversationRepository {

    /**
     * Messages visible in the transcript for the conversation {@code c}, respecting the clear
     * watermark. Correlated so it can be selected alongside the conversation row.
     */
    private static final String VISIBLE_COUNT =
            """
            (select count(*)
               from chat_message m
              where m.conversation_id = c.id
                and m.seq > coalesce(c.cleared_through_seq, -1))
            """;

    private final JdbcClient db;

    ConversationRepository(JdbcClient db) {
        this.db = db;
    }

    UUID create(UUID leadId, String title) {
        UUID id = UUID.randomUUID();
        db.sql("insert into conversation (id, lead_id, title) values (:id, :leadId, :title)")
                .param("id", id)
                .param("leadId", leadId)
                .param("title", title)
                .update();
        return id;
    }

    Optional<UUID> findIdByLead(UUID leadId) {
        return db.sql("select id from conversation where lead_id = :leadId")
                .param("leadId", leadId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Stops the agent answering, and records why. Only the first close counts: a person who
     * opts out after a callback was booked should not have that booking overwritten, and
     * neither should the note recording what they asked for.
     */
    void close(UUID id, String reason, String note) {
        db.sql(
                        """
                        update conversation
                           set closed_at = now(), closed_reason = :reason, closing_note = :note
                         where id = :id and closed_at is null
                        """)
                .param("id", id)
                .param("reason", reason)
                .param("note", note)
                .update();
    }

    boolean exists(UUID id) {
        return db.sql("select count(*) from conversation where id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    Optional<ConversationSummary> find(UUID id) {
        return db.sql(
                        """
                        select c.id, c.lead_id, c.title, %s as message_count,
                               c.closed_at, c.closed_reason, c.closing_note
                          from conversation c
                         where c.id = :id
                        """
                                .formatted(VISIBLE_COUNT))
                .param("id", id)
                .query(ConversationSummary.class)
                .optional();
    }

    /**
     * Recently used conversations that actually have messages, most recent first. Runs where the
     * user hit Ctrl-D immediately leave an empty conversation behind; skipping those keeps
     * {@code --resume} pointing at real history rather than at an empty shell.
     */
    List<ConversationSummary> recentWithMessages(int limit) {
        return db.sql(
                        """
                        select c.id, c.lead_id, c.title, %s as message_count,
                               c.closed_at, c.closed_reason, c.closing_note
                          from conversation c
                         where exists (select 1 from chat_message m where m.conversation_id = c.id)
                         order by c.last_used_at desc
                         limit :limit
                        """
                                .formatted(VISIBLE_COUNT))
                .param("limit", limit)
                .query(ConversationSummary.class)
                .list();
    }

    /**
     * The admin listing: conversations with anything in them, most recently used first, each
     * carrying whoever it was with and whatever verdict it already has.
     *
     * <p>One query rather than a listing plus a review lookup per row, because the page is a
     * table and the alternative is a page of round trips.
     */
    List<ReviewableConversation> forReview(boolean unreviewedOnly, int limit) {
        return db.sql(
                        """
                        select c.id,
                               l.name  as lead_name,
                               c.title,
                               %s      as message_count,
                               c.closed_reason,
                               c.last_used_at,
                               r.rating,
                               r.comment
                          from conversation c
                          left join lead l on l.id = c.lead_id
                          left join conversation_review r on r.conversation_id = c.id
                         where exists (select 1 from chat_message m where m.conversation_id = c.id)
                           and (:unreviewedOnly = false or r.conversation_id is null)
                         order by c.last_used_at desc
                         limit :limit
                        """
                                .formatted(VISIBLE_COUNT))
                .param("unreviewedOnly", unreviewedOnly)
                .param("limit", limit)
                .query(ReviewableConversation.class)
                .list();
    }

    /** The conversation {@code --resume} picks up: the most recent one with any history. */
    Optional<UUID> mostRecentWithMessages() {
        return recentWithMessages(1).stream().findFirst().map(ConversationSummary::id);
    }

    void touch(UUID id) {
        db.sql("update conversation set last_used_at = now() where id = :id").param("id", id).update();
    }

    /** Sets a title only if there isn't one, so the first message of a run names it. */
    void setTitleIfAbsent(UUID id, String title) {
        db.sql("update conversation set title = :title where id = :id and title is null")
                .param("id", id)
                .param("title", title)
                .update();
    }

    Optional<String> title(UUID id) {
        return db.sql("select title from conversation where id = :id")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    /**
     * Messages visible in the transcript, respecting the clear watermark. This is the whole
     * transcript, not the memory window, so it is the honest number to report when resuming.
     */
    int messageCount(UUID id) {
        // Zero for an unknown conversation, which selects no row at all.
        return db.sql("select %s from conversation c where c.id = :id".formatted(VISIBLE_COUNT))
                .param("id", id)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
