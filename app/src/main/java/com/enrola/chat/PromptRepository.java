package com.enrola.chat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Prompt versions, and which ones a conversation ran on. */
@Repository
class PromptRepository {

    private static final String COLUMNS = "id, kind, version, body, is_current as current, created_at, created_by";

    private final JdbcClient db;

    PromptRepository(JdbcClient db) {
        this.db = db;
    }

    Optional<Prompt> current(PromptKind kind) {
        return db.sql("select " + COLUMNS + " from prompt where kind = :kind and is_current")
                .param("kind", kind.name())
                .query(Prompt.class)
                .optional();
    }

    /** Every current version, in the order the kinds are declared. */
    List<Prompt> allCurrent() {
        return db.sql("select " + COLUMNS + " from prompt where is_current")
                .query(Prompt.class)
                .list();
    }

    /** Newest first: the history page reads top down, and the newest is what matters most. */
    List<Prompt> history(PromptKind kind) {
        return db.sql("select " + COLUMNS + " from prompt where kind = :kind order by version desc")
                .param("kind", kind.name())
                .query(Prompt.class)
                .list();
    }

    Optional<Prompt> find(PromptKind kind, int version) {
        return db.sql("select " + COLUMNS + " from prompt where kind = :kind and version = :version")
                .param("kind", kind.name())
                .param("version", version)
                .query(Prompt.class)
                .optional();
    }

    boolean anyFor(PromptKind kind) {
        return db.sql("select count(*) from prompt where kind = :kind")
                        .param("kind", kind.name())
                        .query(Integer.class)
                        .single()
                > 0;
    }

    int nextVersion(PromptKind kind) {
        return db.sql("select coalesce(max(version), 0) + 1 from prompt where kind = :kind")
                .param("kind", kind.name())
                .query(Integer.class)
                .single();
    }

    /** Steps the live version aside. Must run before inserting a new one: the index allows one. */
    void demoteCurrent(PromptKind kind) {
        db.sql("update prompt set is_current = false where kind = :kind and is_current")
                .param("kind", kind.name())
                .update();
    }

    UUID insert(PromptKind kind, int version, String body, boolean current, String author, Instant at) {
        UUID id = UUID.randomUUID();
        db.sql(
                        """
                        insert into prompt (id, kind, version, body, is_current, created_at, created_by)
                        values (:id, :kind, :version, :body, :current, :at, :author)
                        """)
                .param("id", id)
                .param("kind", kind.name())
                .param("version", version)
                .param("body", body)
                .param("current", current)
                // PgJDBC cannot infer a SQL type for Instant; it takes OffsetDateTime.
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("author", author)
                .update();
        return id;
    }

    /** Fixes the versions a conversation runs on. Called once, when it opens. */
    void pin(UUID conversationId, Collection<UUID> promptIds) {
        for (UUID promptId : promptIds) {
            db.sql(
                            """
                            insert into conversation_prompt (conversation_id, prompt_id)
                            values (:conversationId, :promptId)
                            on conflict do nothing
                            """)
                    .param("conversationId", conversationId)
                    .param("promptId", promptId)
                    .update();
        }
    }

    /** What a conversation ran on. Empty for conversations that predate pinning. */
    List<Prompt> usedBy(UUID conversationId) {
        return db.sql(
                        """
                        select p.id, p.kind, p.version, p.body, p.is_current as current,
                               p.created_at, p.created_by
                          from conversation_prompt cp
                          join prompt p on p.id = cp.prompt_id
                         where cp.conversation_id = :id
                         order by p.kind
                        """)
                .param("id", conversationId)
                .query(Prompt.class)
                .list();
    }

    /** Used by tests to prove the partial unique index is doing the work. */
    int currentCount(PromptKind kind) {
        return db.sql("select count(*) from prompt where kind = :kind and is_current")
                .param("kind", kind.name())
                .query(Integer.class)
                .single();
    }

    Optional<OffsetDateTime> createdAt(UUID promptId) {
        return db.sql("select created_at from prompt where id = :id")
                .param("id", promptId)
                .query(OffsetDateTime.class)
                .optional();
    }
}
