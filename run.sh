#!/usr/bin/env bash
# Builds minidb with plain javac (no network, no Maven needed) and starts it.
#
#   ./run.sh                          # TCP server on port 6969, data in ./data
#   ./run.sh --port 7000 --data-dir /tmp/mydb
#   ./run.sh repl                     # embedded REPL, no network
#   ./run.sh client                   # interactive client for a running server
#   ./run.sh client -e "SHOW TABLES"  # one statement
#
# Environment overrides: MINIDB_DATA_DIR, MINIDB_DB, MINIDB_PORT, MINIDB_CACHE_PAGES, JAVA_OPTS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSES="$ROOT/target/classes"

build() {
    local stamp="$CLASSES/.build-stamp"
    if [[ -f "$stamp" ]] && [[ -z "$(find "$ROOT/src/main/java" -name '*.java' -newer "$stamp" -print -quit)" ]]; then
        return
    fi
    echo "Compiling minidb..." >&2
    rm -rf "$CLASSES"
    mkdir -p "$CLASSES"
    find "$ROOT/src/main/java" -name '*.java' > "$ROOT/target/sources.txt"
    javac --release 21 -Xlint:all -encoding UTF-8 -d "$CLASSES" @"$ROOT/target/sources.txt"
    touch "$stamp"
}

mkdir -p "$ROOT/target"
build

if [[ "${1:-}" == "client" ]]; then
    shift
    exec java ${JAVA_OPTS:-} -cp "$CLASSES" com.minidb.server.DbClient "$@"
fi
exec java ${JAVA_OPTS:-} -cp "$CLASSES" com.minidb.Main "$@"
