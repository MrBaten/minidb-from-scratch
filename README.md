# minidb-from-scratch

A small but real relational database engine, written from scratch in **Java 21 with zero
dependencies**: no SQLite, no embedded DB library, no JUnit — just the JDK.

It stores data in a custom paged file, indexes it with a disk-resident **B+ tree**, caches
pages in a **buffer pool with Clock eviction**, makes writes atomic and crash-safe with a
**rollback journal**, parses SQL with a hand-written recursive-descent parser, and serves
many clients over TCP using **virtual threads**.

## Features

| Area | What is implemented |
|---|---|
| Storage | Single `.db` file of 4096-byte pages; fixed 260-byte rows (4 B id + 1 B length + 255 B UTF-8 name) in slotted heap pages |
| Index | B+ tree, one node per page (340 entries per leaf), insert / search / update / delete / range scan, leaf chain for range queries, node split with separator promotion, fixed root page |
| Cache | Buffer pool of 100 frames, **Clock** (second-chance) eviction using ref bit + dirty bit, pin counts, dirty write-back on eviction |
| Transactions | `BEGIN` / `COMMIT` / `ROLLBACK`, autocommit for single statements, before-image **rollback journal** (`<db>.journal`) with CRC32-checked records |
| Recovery | Hot-journal replay on startup: a crash (even `kill -9`) mid-transaction rolls back to the last commit |
| SQL | `CREATE TABLE`, `INSERT` (multi-row), `SELECT` (`*` or columns), `UPDATE`, `DELETE`, `WHERE` with `= < > <= >=`, `AND`, `BETWEEN`, `SHOW TABLES` |
| Server | TCP on port 6969, one virtual thread + one session per client, serializable isolation via a global fair lock |
| Tooling | CLI client with one-shot, script, timing, interactive and parallel benchmark modes |

## Architecture

```
  Client (DbClient / nc)
     │  one SQL statement per line  →  result text + "END"
     ▼
  DbServer ─────────── virtual thread per connection, one session each
     │
     ▼
  SqlParser ────────── tokenizer + recursive descent → Statement (sealed AST)
     │
     ▼
  QueryExecutor ────── autocommit / BEGIN..COMMIT, global lock, WHERE → key range
     │
     ▼
  Catalog + Table ──── page 0 = catalog, heap pages hold 260-byte rows
     │
     ▼
  B+ Tree ──────────── id → row address (page, slot); one node = one page
     │
     ▼
  BufferPool ───────── 100 frames, Clock eviction, pins, dirty write-back ──▶ Journal (.journal)
     │                                                                          before-images
     ▼
  Pager ────────────── file = array of 4096-byte pages
     │
     ▼
  Disk (<name>.db)
```

Source layout (`src/main/java/com/minidb/`):

| File | Role |
|---|---|
| `Main.java` | Entry point: `server` (default) or embedded `repl` |
| `Database.java` | Wires the stack together, startup recovery, transaction control, global lock |
| `storage/Page.java`, `storage/Pager.java` | Page buffer and paged file I/O |
| `cache/BufferPool.java` | Page cache with Clock eviction |
| `index/BPlusTree.java`, `index/BPlusTreeNode.java` | Disk B+ tree and its node page layout |
| `table/Catalog.java`, `table/Table.java`, `table/Row.java` | Schema catalog, heap storage, row format |
| `query/SqlParser.java`, `query/Statement.java` | SQL parser and AST |
| `query/QueryExecutor.java`, `query/QueryResult.java` | Statement execution and result rendering |
| `transaction/Journal.java` | Rollback journal and crash recovery |
| `server/DbServer.java`, `server/DbClient.java` | TCP server, client CLI and load tester |

The design, file ownership and interface contracts are documented in [PLAN.md](PLAN.md).

## How to run

Requirements: JDK 21 (`java` and `javac` on the PATH). Maven is optional. Works on macOS
(Intel and Apple Silicon) and Linux.

On macOS with Homebrew:

```bash
brew install openjdk@21
export PATH="$(brew --prefix openjdk@21)/bin:$PATH"   # add to ~/.zshrc to keep it
java -version                                         # should print 21
```

```bash
./run.sh                                   # build with javac and start the server on :6969 (data in ./data)
./run.sh --port 7000 --data-dir /tmp/mydb  # custom port and data directory
./run.sh repl                              # embedded REPL, no network

./run.sh client                            # interactive client (in another terminal)
./run.sh client -e "SHOW TABLES"           # one statement
./run.sh client --file script.sql          # run a script, one statement per line
./run.sh client --bench 10 100             # 10 parallel clients × 100 queries

./test.sh                                  # build, run all unit tests and the 9 server checks
```

With Maven: `mvn package` then `java -jar target/minidb-from-scratch-1.0.0.jar --port 6969`.

Configuration (flags win over environment variables):

| Flag | Env var | Default |
|---|---|---|
| `--data-dir` | `MINIDB_DATA_DIR` | `./data` |
| `--db` | `MINIDB_DB` | `minidb` (files `minidb.db`, `minidb.journal`) |
| `--port` | `MINIDB_PORT` | `6969` |
| `--cache-pages` | `MINIDB_CACHE_PAGES` | `100` |

Any line-based TCP tool works as a client too: `nc localhost 6969`, type a statement, and
the reply ends with a line `END`.

## 10 example SQL commands

```sql
CREATE TABLE users (id INT, name VARCHAR(255));
INSERT INTO users VALUES (1, 'Jewel');
INSERT INTO users VALUES (2, 'Baten'), (3, 'Rahim'), (4, 'O''Brien');
SELECT * FROM users WHERE id = 1;
SELECT name FROM users WHERE id > 1 AND id <= 3;
SELECT * FROM users WHERE id BETWEEN 2 AND 4;
UPDATE users SET name = 'Karim' WHERE id = 3;
DELETE FROM users WHERE id = 1;
BEGIN;  INSERT INTO users VALUES (5, 'temp');  ROLLBACK;
SHOW TABLES;
```

Example session:

```
minidb> SELECT * FROM users WHERE id BETWEEN 2 AND 4
+----+---------+
| id | name    |
+----+---------+
| 2  | Baten   |
| 3  | Karim   |
| 4  | O'Brien |
+----+---------+
(3 rows)
```

## How it works

**Pages and rows.** The database file is an array of 4096-byte pages. Page 0 is the
catalog (table names, column names, B+ tree root, heap page chain, row count). Rows live
in heap pages: 15 slots of 260 bytes plus a slot bitmap. A row's address (RID) is
`(page << 16) | slot`.

**B+ tree.** Every table has a B+ tree on its INT key mapping `id → RID`. Leaves hold up to
340 `(key, RID)` entries and are linked left-to-right, so `WHERE id > 100 AND id < 200`
descends once and then walks the leaf chain. A full node splits in half: a leaf copies its
first right-hand key up, an internal node moves its median up. The root never moves: when
it splits, its halves move into two new pages, so the catalog never needs updating.
Deletes are lazy (no merging): simpler and safe, at the cost of some empty space.

**Buffer pool and Clock.** All page access goes through a 100-frame cache. Each frame has a
pin count, a reference bit and a dirty bit. To find a victim, the clock hand sweeps the
frames: pinned frames are skipped, frames with ref=1 get a second chance (bit cleared),
the first frame with ref=0 is evicted — written back first if dirty. This approximates LRU
at O(1) cost per access.

**Rollback journal.** Before a page is modified for the first time in a transaction, the
buffer pool copies its original bytes to `<db>.journal` (with a CRC32). Before any dirty
page reaches the `.db` file, the journal is fsynced (write-ahead rule). COMMIT writes and
fsyncs the dirty pages, then deletes the journal — the deletion is the commit point.
ROLLBACK writes the before-images back and truncates pages added by the transaction. If
the process dies mid-transaction, the next start finds the "hot" journal and replays it.

**Concurrency.** Each TCP client gets its own virtual thread and session. Statements are
serialized by one fair `ReentrantLock` (serializable isolation); `BEGIN` holds the lock
until `COMMIT`/`ROLLBACK`, and a disconnect rolls back automatically.

## Testing

`./test.sh` runs three phases:

1. **Build** with `javac -Xlint:all -Werror`.
2. **Unit and engine tests** — one plain-`main` test class per module (Pager, B+ tree,
   buffer pool, table, parser, executor, journal, server), an end-to-end `EngineTest`
   (5000 rows through an 8-page cache, rollback after dirty evictions, randomized workload
   checked against a `TreeMap` model across reopens) and `CrashRecoveryTest` (SIGKILLs a
   server process mid-transaction and verifies recovery).
3. **Server checks** against a live server on port 6969: CREATE → OK; 1000 inserts;
   point query in < 10 ms; range returns 99 rows; delete; BEGIN/ROLLBACK; BEGIN/COMMIT;
   persistence after `kill -9`; 10 parallel clients × 100 queries with 0 errors.

## Limitations (by design, to keep the engine small)

- Fixed schema shape: one INT primary key column plus one VARCHAR(≤255) column per table.
- `WHERE` supports `AND` only (no `OR`, `!=`); only the key column uses the index.
- Lazy B+ tree deletion (no node merging). Freed heap slots are reused through a per-table
  free list, but empty pages are never returned to the operating system.
- One writer or reader at a time (global lock) — simple and serializable, not highly
  concurrent. A client that holds `BEGIN` open makes others wait (up to 30 s, then
  `ERROR: database is locked`).
- Sessions are bound to threads (the lock is reentrant per thread): use one
  `QueryExecutor` per thread when embedding the engine. The TCP server already does this.
- No `DROP TABLE`, joins, aggregates or secondary indexes.

## How it was built

The engine was designed and built with a multi-agent AI workflow (Claude):

1. **Planning** — a lead agent wrote [PLAN.md](PLAN.md): architecture, page formats,
   exact file ownership and interface contracts, plus compile-checked code stubs.
2. **Parallel execution** — 8 subagents each implemented one module (Pager, B+ tree,
   buffer pool, table/catalog, parser, executor, journal, server) and its unit tests,
   owning only their own files and testing against stand-ins for their dependencies.
3. **Integration** — the lead agent merged the modules and added end-to-end and
   crash-recovery tests.
4. **Independent review** — a separate agent that wrote none of the code reviewed it with
   randomized crash simulations; the gaps it found were fixed by each file's owner.

The notes below summarize the key ideas behind each layer.

## What I learned

- **Pages are the unit of everything.** Once the file is "an array of 4096-byte pages",
  caching, journaling and indexing all become operations on page numbers.
- **B+ trees are about fan-out.** 340 keys per leaf means a million rows are 3 levels deep:
  a point lookup touches 3 pages. Keeping the root page fixed removes a whole class of
  catalog-update bugs.
- **The buffer pool decides correctness, not just speed.** Pin counts stop pages from being
  evicted while in use, and the dirty bit plus the write-ahead rule (journal fsync before
  page write) is what makes a crash survivable.
- **Clock is a cheap LRU.** One bit per frame gives "recently used pages survive" without a
  linked list update on every access.
- **A rollback journal is a small, robust form of WAL.** SQLite's classic design: save
  before-images, make deleting the journal the commit point, replay it after a crash.
- **Keep no state above the cache.** Because nothing above the buffer pool caches on-disk
  data, rollback is just "drop the cache and restore the pages".
- **Virtual threads make thread-per-client cheap** again — but lock ownership is per
  thread, so a session must live on one thread from start to finish.
