-- Leads, and the two ways a conversation ends.
--
-- A lead is someone who was comparing health insurance and left their details. The
-- agent opens the conversation with them, so a lead exists before any transcript does.

create table lead (
    id               uuid        primary key,
    name             text        not null,
    -- E.164. Stored because a real deployment texts it; nothing sends to it yet.
    mobile           text        not null,
    email            text,
    -- NSW VIC QLD SA WA TAS NT ACT. Validated at the API and stored uppercase rather
    -- than constrained here, so a bulk import fails with a message naming the row.
    state            text,
    -- Null means no cover yet: a first-timer, not a switcher. The two are different
    -- conversations -- loading and the surcharge, versus carrying waiting periods over.
    current_provider text,
    current_premium  numeric(10, 2),  -- dollars per month
    -- Null blocks outreach. Texting someone who never agreed to be texted is the one
    -- mistake this system must not be able to make by accident.
    consent_at       timestamptz,
    -- new -> awaiting_reply -> engaged -> handed_off | opted_out. Deliberately not a
    -- CHECK constraint: the pipeline will grow states, and each one should not be a
    -- migration. The service is the only writer.
    status           text        not null default 'new',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

-- Supports the operator's question: which leads are waiting on me?
create index lead_status_idx on lead (status, created_at desc);

alter table conversation
    add column lead_id       uuid references lead (id) on delete cascade,
    -- Set when the agent stops answering: it has handed off, or they opted out.
    add column closed_at     timestamptz,
    add column closed_reason text,   -- 'callback' | 'opted_out'
    add column closing_note  text;   -- their words: "tomorrow arvo, after 4"

-- One conversation per lead. A second outreach to the same person would restart the
-- history they already have, so the database refuses rather than the service alone.
create unique index conversation_lead_id_key on conversation (lead_id);
