-- Callback slots.
--
-- One consultant, so the slot is the booking: there is no calendar and no resource to
-- join against, and a time is either free or it is not. When this is replaced by a real
-- scheduling provider it is the Diary interface that changes, not this table.

create table booking (
    id              uuid        primary key,
    conversation_id uuid        not null references conversation (id) on delete cascade,
    lead_id         uuid        references lead (id) on delete set null,
    starts_at       timestamptz not null,
    topic           text,
    -- What they actually said, kept beside the resolved time so a human can see whether
    -- "Thursday arvo" was read the way they meant it.
    requested_words text,
    created_at      timestamptz not null default now(),

    -- Two conversations racing for the same time means one of them loses here, rather
    -- than both people being told yes. Also the index availability is read through.
    constraint booking_starts_at_key unique (starts_at),

    -- Slots sit on the quarter hour. The `at time zone 'UTC'` is not decoration: extract()
    -- from a bare timestamptz depends on the session TimeZone and so is not immutable,
    -- and Postgres refuses non-immutable expressions in a CHECK. Converting to a fixed
    -- zone first makes it immutable, and any whole- or half-hour business zone lands on
    -- the same 15-minute grid either way.
    constraint booking_aligned check (
        extract(minute from (starts_at at time zone 'UTC')) in (0, 15, 30, 45)
        and extract(second from (starts_at at time zone 'UTC')) = 0)
);

-- Bookings are worked front to back: what is coming up, and for whom.
create index booking_conversation_id_idx on booking (conversation_id);
