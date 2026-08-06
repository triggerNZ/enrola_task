-- The agent's instructions, versioned.
--
-- Seeded on first start from the files that used to be read at startup, and edited from
-- the admin pages after that. Append-only: a new version never rewrites an old one, so a
-- conversation reviewed next month can still be read against what the agent was told at
-- the time.

create table prompt (
    id         uuid        primary key,
    -- BRIEF | FAQ | OUTREACH, stored as the enum constant so it maps straight back to
    -- PromptKind. Text with no CHECK, for the reason chat_message.type has none: the set
    -- of prompts will grow, and a fourth should not need a migration.
    kind       text        not null,
    version    integer     not null,
    body       text        not null,
    is_current boolean     not null default false,
    created_at timestamptz not null default now(),
    -- The admin who saved it. Null on the version seeded from the repo.
    created_by text,

    constraint prompt_kind_version_key unique (kind, version)
);

-- Exactly one current version per kind, enforced rather than trusted: "which prompt is
-- live" is the one question this table has to answer unambiguously.
create unique index prompt_one_current_per_kind on prompt (kind) where is_current;

-- Which versions a conversation ran on, fixed when it opened. No cascade to prompt: a
-- version a conversation was judged against must not be deletable.
create table conversation_prompt (
    conversation_id uuid not null references conversation (id) on delete cascade,
    prompt_id       uuid not null references prompt (id),
    primary key (conversation_id, prompt_id)
);
