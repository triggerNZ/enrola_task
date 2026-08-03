package com.enrola;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Conversation rows: creating them, finding one to resume, and keeping them tidy. */
@Repository
class ConversationRepository {

    private final JdbcClient db;

    ConversationRepository(JdbcClient db) {
        this.db = db;
    }

    UUID create(String title) {
        UUID id = UUID.randomUUID();
        db.sql("insert into conversation (id, title) values (:id, :title)")
                .param("id", id)
                .param("title", title)
                .update();
        return id;
    }

    boolean exists(UUID id) {
        return db.sql("select count(*) from conversation where id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /**
     * The most recently used conversation that actually has messages. Runs where the user
     * hit Ctrl-D immediately leave an empty conversation behind; skipping those keeps
     * {@code --resume} pointing at real history.
     */
    Optional<UUID> mostRecentWithMessages() {
        return db.sql(
                        """
                        select c.id
                          from conversation c
                         where exists (select 1 from chat_message m where m.conversation_id = c.id)
                         order by c.last_used_at desc
                         limit 1
                        """)
                .query(UUID.class)
                .optional();
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
     * transcript, not the memory window, so it is the honest number to report at startup.
     */
    int messageCount(UUID id) {
        return db.sql(
                        """
                        select count(*)
                          from chat_message m
                          join conversation c on c.id = m.conversation_id
                         where m.conversation_id = :id
                           and m.seq > coalesce(c.cleared_through_seq, -1)
                        """)
                .param("id", id)
                .query(Integer.class)
                .single();
    }
}
