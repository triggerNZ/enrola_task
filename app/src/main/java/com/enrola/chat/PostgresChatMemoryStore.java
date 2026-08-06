package com.enrola.chat;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the complete transcript in Postgres, one row per message.
 *
 * <p>The chat memory only ever hands this store a windowed slice of the conversation, so
 * {@link #updateMessages} reconciles that slice against the stored transcript and appends
 * what is new -- see {@link MessageReconciler}. Nothing is ever overwritten or deleted.
 */
@Repository
class PostgresChatMemoryStore implements ChatMemoryStore {

    private static final String SELECT_MESSAGES =
            """
            select m.content
              from chat_message m
              join conversation c on c.id = m.conversation_id
             where m.conversation_id = :id
               and m.seq > coalesce(c.cleared_through_seq, -1)
             order by m.seq
            """;

    private static final String SELECT_MAX_SEQ =
            "select coalesce(max(seq), -1) from chat_message where conversation_id = :id";

    // The cast is required: PgJDBC sends a Java String as varchar, and Postgres will not
    // implicitly cast varchar to jsonb.
    private static final String INSERT_MESSAGE =
            """
            insert into chat_message (conversation_id, seq, type, content)
            values (:id, :seq, :type, cast(:content as jsonb))
            """;

    // Moving the watermark rather than deleting rows keeps the transcript durable while
    // still honouring the ChatMemory.clear() contract. The outer coalesce matters: without
    // it, clearing a conversation that has no messages would reset an existing watermark
    // back to null and resurrect everything before it.
    private static final String SOFT_CLEAR =
            """
            update conversation c
               set cleared_through_seq = coalesce(
                       (select max(seq) from chat_message where conversation_id = c.id),
                       c.cleared_through_seq)
             where c.id = :id
            """;

    private final JdbcClient db;

    PostgresChatMemoryStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return db.sql(SELECT_MESSAGES)
                .param("id", conversationId(memoryId))
                .query(String.class)
                .list()
                .stream()
                .map(ChatMessageDeserializer::messageFromJson)
                .toList();
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        UUID id = conversationId(memoryId);

        // MessageWindowChatMemory passes the very list it just mutated, so copy before use.
        List<ChatMessage> window = List.copyOf(messages);
        List<ChatMessage> toAppend = MessageReconciler.newMessages(getMessages(id), window);
        if (toAppend.isEmpty()) {
            return; // the common no-op: the memory only trimmed, nothing was added
        }

        // seq always comes from the database inside this transaction. Two writers racing
        // land on the same base and one loses on the unique (conversation_id, seq)
        // constraint -- a loud failure rather than silently interleaved history.
        int seq = db.sql(SELECT_MAX_SEQ).param("id", id).query(Integer.class).single();
        for (ChatMessage message : toAppend) {
            db.sql(INSERT_MESSAGE)
                    .param("id", id)
                    .param("seq", ++seq)
                    .param("type", message.type().name())
                    .param("content", ChatMessageSerializer.messageToJson(message))
                    .update();
        }
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        db.sql(SOFT_CLEAR).param("id", conversationId(memoryId)).update();
    }

    /** Rows kept for a conversation, ignoring the clear watermark. Used by tests. */
    int storedRowCount(UUID conversationId) {
        return db.sql("select count(*) from chat_message where conversation_id = :id")
                .param("id", conversationId)
                .query(Integer.class)
                .single();
    }

    /** Sequence numbers in transcript order, ignoring the clear watermark. Used by tests. */
    List<Integer> storedSequences(UUID conversationId) {
        return new ArrayList<>(
                db.sql("select seq from chat_message where conversation_id = :id order by seq")
                        .param("id", conversationId)
                        .query(Integer.class)
                        .list());
    }

    /**
     * The store is keyed by conversation UUID. Accepting a bare String too keeps callers
     * simple, but anything else -- notably langchain4j's default memory id, the literal
     * string "default" -- should fail with a message that names the problem rather than
     * surfacing as a confusing SQL error.
     */
    private static UUID conversationId(Object memoryId) {
        if (memoryId instanceof UUID uuid) {
            return uuid;
        }
        if (memoryId instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "chat memory id must be a conversation UUID, got: " + text, e);
            }
        }
        throw new IllegalArgumentException(
                "chat memory id must be a UUID, got "
                        + (memoryId == null ? "null" : memoryId.getClass().getName()));
    }
}
