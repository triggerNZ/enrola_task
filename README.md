## Features

- Stored conversation history. Chat can be resumed when a message arrives
- STOP ends a conversation
- Tools for:
  - Looking up prices and cover
  - Booking a callback session (minimal calendar implementation)
- Admins can approve conversations or log comments against them (minimal UX)
- Prompts are stored, rather than hardcoded. Admins can edit. Past versions are stored.

## Running it

There is no SMS gateway. The agent talks over an HTTP API, and `chat.sh` stands in for the
handset — it prints each reply as it would arrive, with the number of SMS it would cost.

### You need

- **Java 21+** — `java -version`. On a Mac: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
- **Docker**, for Postgres and for the Testcontainers tests
- **curl** and **jq**, which is all `chat.sh` is built from
- An **OpenAI API key**

### Set up

```bash
cp .env.example .env      # then fill it in
```

`.env` is gitignored and holds three things that matter:

| | |
| --- | --- |
| `OPENAI_API_KEY` | the app refuses to start without it |
| `ADMIN_PASSWORD` | guards `/admin`; the app refuses to start without it too |
| `POSTGRES_PASSWORD` | anything you like; Compose and Spring both read it from here |

Postgres is published on **5433**, not 5432, because 5432 is usually taken.

### Run

```bash
docker compose up -d      # Postgres
./serve.sh                # builds the jar and serves on :8080
```

The first start runs the migrations and seeds the three prompts from `knowledge/*.md`. After
that the database is the source of truth for them and the files are only a starting point.

In another terminal, play the person receiving the text:

```bash
./chat.sh --name="Lauren" --mobile=+61400000000 --state=NSW --provider=Bupa --premium=250
```

The agent texts first; you reply as Lauren; `Ctrl-D` ends it. Leave `--provider` off to play
someone with no cover yet, which is a different conversation. `./chat.sh --help` for the rest —
resuming, and picking up an existing lead.

### The admin UI

<http://localhost:8080/admin> — log in as `admin` with your `ADMIN_PASSWORD`.

- **Conversations** — read a transcript, including every tool call, its arguments and what it
  returned. Rate it out of five and say what should have gone better.
- **Prompts** — the agent brief, the FAQ and the outreach instruction, each versioned. Editing
  any version appends a new current one; reverting is just editing an old version and saving it.
  A conversation is pinned to the versions it opened with, so review shows what it actually ran
  on and whether that is still in force.

### Tests

```bash
./gradlew :app:test
```

Docker needs to be running: the repository and diary tests use Testcontainers. Without it they
report as skipped rather than failing.

### Handy

```bash
./reset-db.sh                 # empty every table, keep the schema and the migrations
ENROLA_PORT=8181 ./serve.sh   # different port
ENROLA_URL=http://localhost:8181 ./chat.sh
```

Worth knowing: the model is set by `openai.model` in `application.properties`, the diary runs on
`booking.timezone` (Australia/Sydney) weekdays 8am–6pm, and `sms.max-parts` caps how long a reply
may run.

## Todo

- A claude skill that looks at comments and suggests prompt improvements.

## Deliberately out of scope
- Calendly integration
- Fancy UI

## Learnings

- GSM-7 vs USC-2
  - Emojis, fancy quotes, etc
- 