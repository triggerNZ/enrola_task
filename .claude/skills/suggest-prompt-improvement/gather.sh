#!/usr/bin/env bash
#
# Pulls the evidence for a prompt suggestion out of Postgres: reviewed conversations, their
# transcripts, and the prompt versions each one actually ran on.
#
# Read-only by construction -- every statement here is a select.
#
# Usage:
#   ./gather.sh reviews [--max-rating N] [--limit N]   reviewed conversations, worst first
#   ./gather.sh conversation <uuid>                    one conversation, in full
#   ./gather.sh prompt <BRIEF|FAQ|OUTREACH> [version]  a prompt body (current if no version)
#   ./gather.sh prompts                                every prompt, version and state

set -euo pipefail

cd "$(dirname "$0")/../../.."

DB="${POSTGRES_DB:-enrola}"
DB_USER="${POSTGRES_USER:-enrola}"

die() {
    printf 'gather.sh: %s\n' "$1" >&2
    exit 1
}

docker compose ps --status running --services 2>/dev/null | grep -qx postgres \
    || die "postgres is not running. Start it with: docker compose up -d"

# -tA so the output is the value and nothing else; ON_ERROR_STOP so a broken query fails here.
q() {
    docker compose exec -T postgres psql -U "$DB_USER" -d "$DB" -tA -v ON_ERROR_STOP=1 -c "$1" </dev/null
}

# The visible transcript of one conversation, oldest first, with tool calls and their arguments.
# UserMessage keeps its words under contents[0]; AiMessage keeps them at the top level.
transcript_sql() {
    cat <<SQL
(select coalesce(json_agg(json_build_object(
          'seq', m.seq,
          'role', m.type,
          'text', coalesce(m.content->>'text', m.content->'contents'->0->>'text'),
          'toolCalls', (select json_agg(json_build_object(
                                 'name', t->>'name', 'arguments', t->>'arguments'))
                          from jsonb_array_elements(m.content->'toolExecutionRequests') t)
        ) order by m.seq), '[]'::json)
   from chat_message m
  where m.conversation_id = c.id
    and m.seq > coalesce(c.cleared_through_seq, -1))
SQL
}

# Which prompt versions the conversation was pinned to. Empty for conversations that opened
# before pinning existed -- say so rather than pretending the current ones applied.
prompts_sql() {
    cat <<SQL
(select coalesce(json_agg(json_build_object(
          'kind', p.kind, 'version', p.version, 'stillCurrent', p.is_current) order by p.kind), '[]'::json)
   from conversation_prompt cp join prompt p on p.id = cp.prompt_id
  where cp.conversation_id = c.id)
SQL
}

case "${1:-reviews}" in
    reviews)
        shift || true
        max_rating=5
        limit=20
        while [ "$#" -gt 0 ]; do
            case "$1" in
                --max-rating) max_rating="$2"; shift 2 ;;
                --limit) limit="$2"; shift 2 ;;
                *) die "unknown argument: $1" ;;
            esac
        done
        q "
        select coalesce(json_agg(row_to_json(x) order by x.rating, x.reviewed_at desc), '[]'::json)
          from (select c.id                      as conversation_id,
                       l.name                    as lead,
                       l.current_provider        as current_provider,
                       r.rating                  as rating,
                       r.comment                 as comment,
                       r.reviewer                as reviewer,
                       r.updated_at              as reviewed_at,
                       c.closed_reason           as ended,
                       $(prompts_sql)            as prompts_used,
                       $(transcript_sql)         as transcript
                  from conversation_review r
                  join conversation c on c.id = r.conversation_id
                  left join lead l on l.id = c.lead_id
                 where r.rating <= $max_rating
                 limit $limit) x;"
        ;;

    conversation)
        [ "${2:-}" ] || die "usage: ./gather.sh conversation <uuid>"
        q "
        select coalesce(json_agg(row_to_json(x)), '[]'::json)
          from (select c.id as conversation_id,
                       l.name as lead,
                       r.rating, r.comment,
                       $(prompts_sql)    as prompts_used,
                       $(transcript_sql) as transcript
                  from conversation c
                  left join lead l on l.id = c.lead_id
                  left join conversation_review r on r.conversation_id = c.id
                 where c.id = '$2') x;"
        ;;

    prompt)
        [ "${2:-}" ] || die "usage: ./gather.sh prompt <BRIEF|FAQ|OUTREACH> [version]"
        kind="$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')"
        if [ "${3:-}" ]; then
            q "select body from prompt where kind = '$kind' and version = $3"
        else
            q "select body from prompt where kind = '$kind' and is_current"
        fi
        ;;

    prompts)
        docker compose exec -T postgres psql -U "$DB_USER" -d "$DB" -P pager=off -v ON_ERROR_STOP=1 -c "
        select kind, version, is_current, coalesce(created_by, '(seeded)') as saved_by,
               length(body) as chars
          from prompt order by kind, version;" </dev/null
        ;;

    *) die "unknown command: $1 (reviews | conversation | prompt | prompts)" ;;
esac
