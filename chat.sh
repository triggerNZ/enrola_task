#!/usr/bin/env bash
#
# Stand in for someone who was comparing health insurance and got a text about it.
#
# The agent opens the conversation; you reply as the lead. Everything lives in the server
# (see ./serve.sh) -- this only reads lines, posts them, and prints the replies as they
# would arrive, with the SMS count. Needs curl and jq; no Java, no Gradle.

set -euo pipefail

ENROLA_URL="${ENROLA_URL:-http://localhost:8080}"
API="$ENROLA_URL/api"

usage() {
    cat <<'EOF'
Usage: ./chat.sh --name="Sam" --mobile=+61400000000 [--state=QLD] [--email=..]
                 [--provider=Bupa] [--premium=250]
                                          create a lead, then have the agent text them
       ./chat.sh --lead=<uuid>            text an existing lead, or resume their conversation
       ./chat.sh --conversation=<uuid>    resume a particular conversation
       ./chat.sh --resume                 resume the most recent one
       ./chat.sh                          a demo lead, so one command still works

Leave --provider off to play someone with no cover yet: a different conversation.
Ctrl-D ends the session. The server is ./serve.sh, at $ENROLA_URL.
EOF
}

die() {
    printf 'chat.sh: %s\n' "$1" >&2
    exit 1
}

for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || die "no '$tool' on PATH; it is required."
done

conversation=""
conversation_given=false
lead=""
resume=false
name=""
mobile=""
email=""
state=""
provider=""
premium=""
for arg in "$@"; do
    case "$arg" in
        --conversation=*)
            conversation="${arg#*=}"
            conversation_given=true
            ;;
        --lead=*) lead="${arg#*=}" ;;
        --name=*) name="${arg#*=}" ;;
        --mobile=*) mobile="${arg#*=}" ;;
        --email=*) email="${arg#*=}" ;;
        --state=*) state="${arg#*=}" ;;
        --provider=*) provider="${arg#*=}" ;;
        --premium=*) premium="${arg#*=}" ;;
        --resume) resume=true ;;
        -h | --help)
            usage
            exit 0
            ;;
        *) die "unknown argument: $arg (try --help)" ;;
    esac
done

if [ "$conversation_given" = true ] && [ -z "$conversation" ]; then
    die "--conversation needs a value, e.g. --conversation=<uuid>."
fi
if [ "$conversation_given" = true ] && [ "$resume" = true ]; then
    printf 'chat.sh: --conversation and --resume both given; using --conversation.\n' >&2
fi

body="$(mktemp "${TMPDIR:-/tmp}/enrola-chat.XXXXXX")"
trap 'rm -f "$body"' EXIT

# api METHOD PATH [JSON] -- writes the response body to $body and prints the status code.
# Fails only when the server could not be reached at all.
api() {
    if [ "$#" -ge 3 ]; then
        curl -sS -X "$1" "$API$2" \
            -H 'content-type: application/json' \
            -d "$3" \
            -o "$body" -w '%{http_code}'
    else
        curl -sS -X "$1" "$API$2" -o "$body" -w '%{http_code}'
    fi
}

# Errors come back as {"error": "..."}; fall back to the raw body for anything else.
api_error() {
    local message
    message="$(jq -r '.error // empty' "$body" 2>/dev/null || true)"
    if [ -z "$message" ]; then
        message="$(tr -d '\n' <"$body" | cut -c 1-200)"
    fi
    printf '%s' "${message:-no detail}"
}

# The message as it would arrive, then what it would cost. Parts of a long message are
# concatenated SMS: the handset reassembles them, so the recipient sees one message and
# printing them separately would misrepresent that. The segment count is the real number
# worth seeing, since it is what gets billed.
print_reply() {
    jq -r '"\nllm> \(.text)"' "$body"
    segments="$(jq -r '.segments' "$body")"
    [ "$segments" = 1 ] || printf '     (%s SMS)\n' "$segments"
}

if ! status="$(api GET '/conversations?limit=1')"; then
    die "no server at $ENROLA_URL. Start one with ./serve.sh, or set ENROLA_URL."
fi
[ "$status" = 200 ] || die "$ENROLA_URL answered $status: $(api_error)"

restored=0
opened=false

if [ "$conversation_given" = true ]; then
    status="$(api GET "/conversations/$conversation")"
    if [ "$status" = 404 ]; then
        printf 'chat.sh: no conversation %s.\n' "$conversation" >&2
        printf "  List existing ones with: curl -s '%s/conversations?limit=20' | jq\n" "$API" >&2
        exit 1
    fi
    [ "$status" = 200 ] || die "could not load $conversation ($status): $(api_error)"
    restored="$(jq -r '.messageCount' "$body")"
elif [ "$resume" = true ]; then
    # The listing skips conversations that never got a message, so the first entry is the
    # most recent one with real history.
    status="$(api GET '/conversations?limit=1')"
    [ "$status" = 200 ] || die "could not list conversations ($status): $(api_error)"
    conversation="$(jq -r '.[0].id // empty' "$body")"
    restored="$(jq -r '.[0].messageCount // 0' "$body")"
    [ -n "$conversation" ] || die "no previous conversation to resume."
else
    if [ -z "$lead" ]; then
        [ -n "$name" ] || name="Sam"
        [ -n "$mobile" ] || mobile="+61400000000"
        payload="$(jq -nc \
            --arg name "$name" --arg mobile "$mobile" --arg email "$email" \
            --arg state "$state" --arg provider "$provider" --arg premium "$premium" \
            '{name: $name, mobile: $mobile, consent: true}
             + (if $email    == "" then {} else {email: $email} end)
             + (if $state    == "" then {} else {state: $state} end)
             + (if $provider == "" then {} else {currentProvider: $provider} end)
             + (if $premium  == "" then {} else {currentPremium: ($premium | tonumber)} end)')"
        status="$(api POST '/leads' "$payload")"
        [ "$status" = 201 ] || die "could not create the lead ($status): $(api_error)"
        lead="$(jq -r '.id' "$body")"
        printf 'Lead %s\n' "$lead"
    fi

    status="$(api POST "/leads/$lead/outreach")"
    if [ "$status" = 409 ]; then
        # Already contacted: pick up where that conversation left off rather than refusing.
        printf 'chat.sh: %s\n' "$(api_error)" >&2
        status="$(api GET "/leads/$lead")"
        [ "$status" = 200 ] || die "could not load lead $lead ($status): $(api_error)"
        conversation="$(jq -r '.conversationId // empty' "$body")"
        [ -n "$conversation" ] || exit 1
        printf 'Resuming their conversation instead.\n' >&2
        status="$(api GET "/conversations/$conversation")"
        restored="$(jq -r '.messageCount // 0' "$body")"
    else
        [ "$status" = 201 ] || die "could not start outreach ($status): $(api_error)"
        conversation="$(jq -r '.conversationId' "$body")"
        opened=true
    fi
fi

printf 'Conversation %s  (%s message(s) restored). Ctrl-D to end.\n' "$conversation" "$restored"
if [ "$opened" = true ]; then
    print_reply
fi

turns=0
# Mirrors the exit code the chat reported when the loop lived in Java: the last turn decides.
exit_status=0

while IFS= read -r -p $'\nyou> ' line; do
    # Skip blank lines rather than spending a turn on them.
    case "$line" in
        *[![:space:]]*) ;;
        *) continue ;;
    esac

    # jq builds the body, so quotes, backslashes and non-ASCII are escaped properly.
    payload="$(jq -nc --arg text "$line" '{text: $text}')"

    if ! status="$(api POST "/conversations/$conversation/messages" "$payload")"; then
        printf '\nchat.sh: %s is not answering.\n' "$ENROLA_URL" >&2
        exit_status=1
        continue
    fi

    if [ "$status" != 200 ]; then
        # Keep going: a failed turn is never persisted, so the next one picks up exactly
        # where this one left off.
        printf '\nchat.sh: turn failed (%s): %s\n' "$status" "$(api_error)" >&2
        exit_status=1
        continue
    fi

    print_reply
    turns=$((turns + 1))
    exit_status=0

    if [ "$(jq -r '.closed' "$body")" = true ]; then
        printf '\n[conversation closed: %s]\n' "$(jq -r '.closedReason' "$body")"
        break
    fi
done

printf '\nConversation ended after %d turn(s).\n' "$turns"
printf 'Resume with: ./chat.sh --conversation=%s\n' "$conversation"
exit "$exit_status"
