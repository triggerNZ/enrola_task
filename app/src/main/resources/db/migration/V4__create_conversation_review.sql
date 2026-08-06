-- What an admin thought of a conversation.
--
-- One review per conversation rather than a history of them: the point is the current
-- verdict, and saving is an upsert so submitting the form twice changes nothing.

create table conversation_review (
    conversation_id uuid        primary key references conversation (id) on delete cascade,
    rating          smallint    not null,
    comment         text,
    -- Who said so, from the authenticated admin user, so a verdict is attributable.
    reviewer        text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    -- Out of five, and unlike chat_message.type that is not a vocabulary which will grow.
    -- A CHECK here will never turn a library upgrade into a migration.
    constraint conversation_review_rating check (rating between 1 and 5)
);

-- The listing sorts by rating to find the bad ones, and filters for the unreviewed.
create index conversation_review_rating_idx on conversation_review (rating);
