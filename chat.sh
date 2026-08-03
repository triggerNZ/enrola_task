#!/usr/bin/env bash
#
# Build the application jar, then run it interactively.
#
# Running the jar directly rather than through `gradlew bootRun` keeps Gradle's
# progress output from redrawing over the chat prompt: the build finishes and
# exits before the conversation starts.
#
# Usage: ./chat.sh          Ctrl-D ends the conversation.

set -euo pipefail

# Run from the repo root so the app finds .env, whatever directory it's invoked from.
cd "$(dirname "$0")"

REQUIRED_JAVA=21

java_major() {
    java -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print v[1]; exit }'
}

current="$(java_major || true)"
if [ -z "$current" ]; then
    echo "chat.sh: no 'java' on PATH. Java ${REQUIRED_JAVA}+ is required." >&2
    exit 1
fi
if [ "$current" -lt "$REQUIRED_JAVA" ]; then
    echo "chat.sh: Java ${REQUIRED_JAVA}+ required, found ${current}." >&2
    echo "  Try: export JAVA_HOME=\$(/usr/libexec/java_home -v ${REQUIRED_JAVA})" >&2
    exit 1
fi

./gradlew :app:bootJar --quiet --console=plain

# exec so Ctrl-C reaches the JVM directly rather than this wrapper.
exec java -jar app/build/libs/app.jar "$@"
