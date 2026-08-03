-- Chat transcripts.
--
-- The transcript is append-only and complete. langchain4j's MessageWindowChatMemory
-- only ever hands the store a *windowed* slice of the conversation, so the store
-- reconciles that slice against what is already here rather than replacing it --
-- see MessageReconciler.

create table conversation (
    id                  uuid primary key,
    title               text,
    created_at          timestamptz not null default now(),
    last_used_at        timestamptz not null default now(),
    -- Soft-clear watermark. ChatMemory.clear() moves this forward instead of
    -- deleting rows, so the transcript stays durable while the memory still
    -- empties. Null means nothing has been cleared.
    cleared_through_seq integer
);

-- Supports `--resume`, which reads the most recently used conversation.
create index conversation_last_used_at_idx on conversation (last_used_at desc);

create table chat_message (
    id              bigserial   primary key,
    conversation_id uuid        not null references conversation (id) on delete cascade,
    -- Dense and monotonic per conversation; defines transcript order. Never reused,
    -- and never reset by a clear.
    seq             integer     not null,
    -- Denormalised from content->>'type' purely for cheap inspection. Deliberately
    -- not a CHECK constraint: langchain4j adds message types across versions, and a
    -- constraint would turn a library upgrade into a migration emergency.
    type            text        not null,
    content         jsonb       not null,
    created_at      timestamptz not null default now(),
    -- Doubles as the read index: `where conversation_id = ? order by seq` is an
    -- index-ordered scan and `max(seq)` is an index-only backward scan. It also
    -- covers the foreign key, so no further indexes are needed.
    constraint chat_message_conversation_seq_key unique (conversation_id, seq)
);
