#!/usr/bin/env bash
#
# Empty every table, keeping the schema.
#
# For when the development database has filled up with half-finished conversations and
# leads from testing. Bookings, transcripts, conversations and leads all go; the tables
# themselves, and Flyway's record of the migrations that built them, stay.

set -euo pipefail

cd "$(dirname "$0")"

usage() {
    cat <<'EOF'
Usage: ./reset-db.sh          show what is there, then ask before emptying it
       ./reset-db.sh --yes    empty it without asking

Empties every table in the development database and leaves the schema alone.

To throw the schema away as well, do not use this: `docker compose down -v` deletes
the volume, and Flyway rebuilds from scratch the next time the app starts.
EOF
}

die() {
    printf 'reset-db.sh: %s\n' "$1" >&2
    exit 1
}

assume_yes=false
for arg in "$@"; do
    case "$arg" in
        -y | --yes) assume_yes=true ;;
        -h | --help)
            usage
            exit 0
            ;;
        *) die "unknown argument: $arg (try --help)" ;;
    esac
done

command -v docker >/dev/null 2>&1 || die "no 'docker' on PATH."

# Compose reads .env itself, but this script needs the same values to drive psql.
#
# An exported variable wins over the file, matching what application.properties promises
# for the application. Sourcing .env over the top would mean `POSTGRES_DB=scratch
# ./reset-db.sh` silently emptying the real database instead -- which is exactly the kind
# of surprise a script like this must not have.
if [ -f .env ]; then
    while IFS='=' read -r key value; do
        case "$key" in
            POSTGRES_DB | POSTGRES_USER)
                # `${!key}` needs bash 4; this works on the bash macOS ships.
                eval "current=\${$key-}"
                [ -n "${current:-}" ] || eval "$key=\$value"
                ;;
        esac
    done <.env
fi
database="${POSTGRES_DB:-enrola}"
db_user="${POSTGRES_USER:-enrola}"

docker compose ps --status running --services 2>/dev/null | grep -qx postgres \
    || die "postgres is not running. Start it with: docker compose up -d"

# ON_ERROR_STOP so a failed statement ends the script rather than carrying on to the
# destructive one. Stdin is closed deliberately: `docker compose exec` reads it even with
# -T, and would otherwise eat the answer to the confirmation prompt below.
psql() {
    docker compose exec -T postgres psql -U "$db_user" -d "$database" -v ON_ERROR_STOP=1 "$@" \
        </dev/null
}

# Every base table in the public schema except Flyway's own. Emptying that one would
# convince Flyway the migrations had never run, and the next startup would try to create
# tables that already exist.
tables="$(psql -tAc "
    select string_agg(quote_ident(tablename), ', ' order by tablename)
      from pg_tables
     where schemaname = 'public'
       and tablename <> 'flyway_schema_history'")"
tables="${tables//$'\n'/}"

if [ -z "$tables" ]; then
    printf 'Nothing to empty: %s has no tables yet.\n' "$database"
    exit 0
fi

printf 'Database "%s" in the postgres container.\n\n' "$database"

# Counting rows per table without naming any: query_to_xml runs a count against each
# table found, so this keeps working when a migration adds one.
psql -c "
    select table_name as \"table\",
           (xpath('/row/c/text()', counted))[1]::text::int as rows
      from (select table_name,
                   query_to_xml(format('select count(*) as c from public.%I', table_name),
                                false, true, '') as counted
              from information_schema.tables
             where table_schema = 'public'
               and table_type = 'BASE TABLE'
               and table_name <> 'flyway_schema_history') t
     order by table_name"

if [ "$assume_yes" != true ]; then
    printf 'This cannot be undone. Type the database name to confirm: '
    # No terminal, or nothing typed, means no confirmation -- not a silent yes.
    read -r answer || answer=""
    [ "$answer" = "$database" ] || die "not confirmed; nothing was changed."
fi

# One statement, so the foreign keys between leads, conversations, messages and bookings
# never have to be emptied in a particular order. RESTART IDENTITY resets chat_message's
# sequence, so an emptied database looks like a new one rather than one that starts at
# id 4000. The lock timeout turns "the server is holding a transaction open" into a clear
# failure instead of a script that hangs.
psql -c "set lock_timeout = '5s'; truncate table $tables restart identity cascade"

printf '\nEmptied: %s\nMigrations left in place.\n' "$tables"
