#!/usr/bin/env bash
# Full test suite for minidb-from-scratch.
#
#   Phase 1  compile main + test sources with javac (no network needed)
#   Phase 2  run every module unit test and the end-to-end engine tests
#   Phase 3  start a real server on TCP port 6969 and run the 9 required checks
#
# Exit status is 0 only if every check passes.
# Environment: MINIDB_TEST_PORT (default 6969), MINIDB_LATENCY_LIMIT_MS (default 10).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${MINIDB_TEST_PORT:-6969}"
LATENCY_LIMIT_MS="${MINIDB_LATENCY_LIMIT_MS:-10}"
CLASSES="$ROOT/target/classes"
TEST_CLASSES="$ROOT/target/test-classes"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/minidb-test.XXXXXX")"
DATA_DIR="$WORK/data"
SERVER_LOG="$WORK/server.log"
SERVER_PID=""
PASSED=0
FAILED=0

RED=$'\e[31m'; GREEN=$'\e[32m'; BOLD=$'\e[1m'; RESET=$'\e[0m'
if [[ ! -t 1 ]]; then RED=""; GREEN=""; BOLD=""; RESET=""; fi

cleanup() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null
        wait "$SERVER_PID" 2>/dev/null
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

pass() { PASSED=$((PASSED + 1)); echo "${GREEN}PASS${RESET} $1"; }
fail() { FAILED=$((FAILED + 1)); echo "${RED}FAIL${RESET} $1"; [[ -n "${2:-}" ]] && echo "$2" | sed 's/^/       | /'; }

# check <name> <output> <grep -E pattern>...  — passes when every pattern matches the output
check() {
    local name="$1" out="$2"; shift 2
    local p
    for p in "$@"; do
        if ! grep -qE -- "$p" <<<"$out"; then
            fail "$name" "missing /$p/ in:"$'\n'"$out"
            return 1
        fi
    done
    pass "$name"
}

client() { java -cp "$CLASSES" com.minidb.server.DbClient --port "$PORT" "$@" 2>&1; }

start_server() {
    java -cp "$CLASSES" com.minidb.Main server --data-dir "$DATA_DIR" --port "$PORT" >>"$SERVER_LOG" 2>&1 &
    SERVER_PID=$!
    if ! client --wait 20 -e "SHOW TABLES" >/dev/null; then
        echo "${RED}server failed to start${RESET}"; cat "$SERVER_LOG"; exit 1
    fi
}

# ---------------------------------------------------------------- Phase 1: build
echo "${BOLD}== Phase 1: build${RESET}"
rm -rf "$CLASSES" "$TEST_CLASSES"
mkdir -p "$CLASSES" "$TEST_CLASSES"
find "$ROOT/src/main/java" -name '*.java' >"$WORK/main-sources.txt"
find "$ROOT/src/test/java" -name '*.java' >"$WORK/test-sources.txt"
if ! javac --release 21 -Xlint:all -Werror -encoding UTF-8 -d "$CLASSES" @"$WORK/main-sources.txt"; then
    echo "${RED}compilation of main sources failed${RESET}"; exit 1
fi
if ! javac --release 21 -encoding UTF-8 -cp "$CLASSES" -d "$TEST_CLASSES" @"$WORK/test-sources.txt"; then
    echo "${RED}compilation of test sources failed${RESET}"; exit 1
fi
touch "$CLASSES/.build-stamp"
pass "build: $(wc -l <"$WORK/main-sources.txt" | tr -d ' ') main + $(wc -l <"$WORK/test-sources.txt" | tr -d ' ') test sources compiled (-Xlint:all -Werror)"

# ---------------------------------------------------------------- Phase 2: unit + engine tests
echo "${BOLD}== Phase 2: unit and engine tests${RESET}"
while IFS= read -r src; do
    cls="${src#"$ROOT/src/test/java/"}"; cls="${cls%.java}"; cls="${cls//\//.}"
    [[ "$cls" == *Test && "$cls" != *.testkit.* ]] || continue
    if out="$(java -cp "$CLASSES:$TEST_CLASSES" "$cls" 2>&1)"; then
        pass "unit: ${cls##*.} ($(grep -Eo '[0-9]+ passed' <<<"$out" | tail -1))"
    else
        fail "unit: ${cls##*.}" "$out"
    fi
done < <(sort "$WORK/test-sources.txt")

# ---------------------------------------------------------------- Phase 3: server checks
echo "${BOLD}== Phase 3: server integration checks (port $PORT)${RESET}"
start_server

# 1. CREATE TABLE
out="$(client -e "CREATE TABLE users (id INT, name VARCHAR(255))")"
check "1. CREATE TABLE users -> OK" "$out" '^OK: table users created$'

# 2. INSERT 1000 rows
for i in $(seq 1 1000); do echo "INSERT INTO users VALUES ($i, 'user_$i')"; done >"$WORK/inserts.sql"
out="$(client --quiet --file "$WORK/inserts.sql")"
all="$(client -e "SELECT * FROM users")"
check "2. INSERT 1000 rows -> all inserted" "$out"$'\n'"$(tail -1 <<<"$all")" 'executed=1000 ok=1000 errors=0' '^\(1000 rows\)$'

# 3. Point query with latency budget
out="$(client --time -e "SELECT * FROM users WHERE id = 500")"
ms="$(grep -Eo 'time_ms=[0-9.]+' <<<"$out" | cut -d= -f2)"
if grep -qE '^\| 500 +\| user_500 +\|$' <<<"$out" && grep -q '^(1 row)$' <<<"$out" \
        && awk -v t="$ms" -v lim="$LATENCY_LIMIT_MS" 'BEGIN { exit !(t != "" && t < lim) }'; then
    pass "3. SELECT WHERE id = 500 -> correct row in ${ms} ms (< ${LATENCY_LIMIT_MS} ms)"
else
    fail "3. SELECT WHERE id = 500 -> correct row in < ${LATENCY_LIMIT_MS} ms" "$out"
fi

# 4. Range query
out="$(client -e "SELECT * FROM users WHERE id > 100 AND id < 200")"
check "4. SELECT WHERE id > 100 AND id < 200 -> 99 rows" "$out" '^\(99 rows\)$' '^\| 101 +\| user_101' '^\| 199 +\| user_199'

# 5. Delete then point query
d="$(client -e "DELETE FROM users WHERE id = 10")"
s="$(client -e "SELECT * FROM users WHERE id = 10")"
check "5. DELETE id=10, then SELECT id=10 -> not found" "$d"$'\n'"$s" '^OK: 1 row deleted$' '^\(0 rows\)$'

# 6. BEGIN / INSERT / ROLLBACK
t="$(printf 'BEGIN\nINSERT INTO users VALUES (5001, %s)\nROLLBACK\n' "'ghost'" | client --file -)"
s="$(client -e "SELECT * FROM users WHERE id = 5001")"
check "6. BEGIN, INSERT, ROLLBACK -> row NOT present" "$t"$'\n'"$s" 'OK: transaction started' 'OK: transaction rolled back' 'errors=0' '^\(0 rows\)$'

# 7. BEGIN / INSERT / COMMIT
t="$(printf 'BEGIN\nINSERT INTO users VALUES (5002, %s)\nCOMMIT\n' "'keeper'" | client --file -)"
s="$(client -e "SELECT * FROM users WHERE id = 5002")"
check "7. BEGIN, INSERT, COMMIT -> row present" "$t"$'\n'"$s" 'OK: transaction committed' 'errors=0' '^\| 5002 +\| keeper +\|$' '^\(1 row\)$'

# 8. Persistence across a hard restart (SIGKILL: no shutdown hook, no final flush)
kill -9 "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null; SERVER_PID=""
start_server
a="$(client -e "SELECT * FROM users")"
b="$(client -e "SELECT * FROM users WHERE id = 500")"
c="$(client -e "SELECT * FROM users WHERE id = 5002")"
e="$(client -e "SELECT * FROM users WHERE id = 10")"
check "8. Restart server (kill -9) -> data still there" "$(tail -1 <<<"$a")"$'\n'"$b"$'\n'"$c"$'\n'"$e" \
    '^\(1000 rows\)$' '^\| 500 +\| user_500' '^\| 5002 +\| keeper' '^\(0 rows\)$'

# 9. Concurrency
out="$(client --bench 10 100)"
check "9. 10 parallel clients x 100 queries -> 0 errors" "$out" 'clients=10 queries=1000 errors=0'
after="$(client -e "SELECT * FROM users WHERE id >= 1000000")"
check "9b. all 500 benchmark inserts visible afterwards" "$(tail -1 <<<"$after")" '^\(500 rows\)$'

# ---------------------------------------------------------------- Summary
echo
if [[ $FAILED -eq 0 ]]; then
    echo "${GREEN}${BOLD}ALL $PASSED CHECKS PASSED${RESET}"
    exit 0
fi
echo "${RED}${BOLD}$FAILED FAILED, $PASSED passed${RESET}"
echo "--- server log ---"; cat "$SERVER_LOG"
exit 1
