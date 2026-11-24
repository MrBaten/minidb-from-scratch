# minidb-from-scratch — Engineering Plan

A from-scratch relational database engine in Java 21 with zero runtime dependencies:
paged storage, a disk-resident B+ tree index, a Clock-eviction buffer pool, a rollback
journal for transactions and crash recovery, a hand-written SQL parser, and a TCP server
that runs every client on a virtual thread.

This document is the single source of truth for architecture, file ownership and
interface contracts. The compile-checked contract skeletons in `src/main/java` (written
by the main agent before any subagent starts) are the authoritative signatures; this
document explains the semantics behind them.

---

## 1. Architecture

```
 TCP client ──line──▶ DbServer (virtual thread per client)
                          │  one QueryExecutor (= session) per connection
                          ▼
                     SqlParser ──▶ Statement (sealed AST)
                          │
                          ▼
                    QueryExecutor ── global DB lock, autocommit / BEGIN..COMMIT
                          │
                          ▼
              Catalog (page 0) ──▶ Table ──▶ heap pages (Row = 260 bytes)
                                     │
                                     ▼
                                 BPlusTree (id → RID)   one node = one page
                                     │
                                     ▼
                     BufferPool (100 frames, Clock: ref bit + dirty bit, pin counts)
                         │                         │
                         │ before-image             │ read / write pages
                         ▼                         ▼
                     Journal (<db>.journal)      Pager (<db>.db, 4096-byte pages)
                                                   │
                                                   ▼
                                                 Disk
```

Layering rule: a module may only call modules *below* it. Nothing above the BufferPool
caches on-disk state in memory (except immutable facts such as a table's B+ tree root
page number). This makes ROLLBACK trivially correct: discard the buffer pool, restore the
journal, and every higher layer re-reads the restored pages.

### 1.1 On-disk files (inside the configurable data directory)

| File              | Contents |
|-------------------|----------|
| `<name>.db`       | Sequence of 4096-byte pages. Page 0 = catalog. Other pages = B+ tree nodes or heap pages. |
| `<name>.journal`  | Rollback journal: before-images of pages modified by the active transaction. Exists only while a write transaction is in flight (or after a crash). |

The assignment text calls the journal `journal.db`; we name it `<name>.journal` so that
several databases can share a data directory and so that `.gitignore`'s `*.journal` rule
applies. Behaviour is identical.

### 1.2 Page types

| Type byte (offset 0) | Page kind | Owner |
|---|---|---|
| — (magic `MINIDB01` at offset 0) | Catalog (page 0 only) | Catalog |
| `1` | B+ tree leaf | BPlusTreeNode |
| `2` | B+ tree internal | BPlusTreeNode |
| `3` | Heap (row storage) | Table |

### 1.3 Row format (fixed, 260 bytes)

```
offset 0   int   id            (4 bytes, big-endian)
offset 4   byte  name length   (1 byte, unsigned 0..255, UTF-8 byte count)
offset 5   byte[255] name      (UTF-8, zero padded)
```

### 1.4 Transactions and durability (rollback journal, SQLite-style)

* Every write statement runs inside a transaction: an implicit one (autocommit) or an
  explicit `BEGIN … COMMIT/ROLLBACK`.
* `Journal.begin(originalPageCount)` starts a transaction (cheap, file created lazily).
* `BufferPool.getPageForWrite(n)` copies the page's current bytes into the journal
  *before* the caller can modify it (once per page per transaction; pages created during
  the transaction are not journaled — truncation removes them).
* Before **any** dirty page is written to the `.db` file while a transaction is active
  (eviction or flush), the BufferPool calls `Journal.sync()` (write-ahead rule for the
  before-images).
* COMMIT: `journal.sync()` → `pool.flushAll()` (writes dirty pages + fsync db) →
  `journal.commit()` (delete journal). Deleting the journal is the commit point.
* ROLLBACK: `pool.discardAll()` → `journal.rollback(pager)` (write before-images back,
  truncate to original page count, fsync, delete journal).
* Crash: at startup, `Journal.recover(path, pager)` replays a hot journal (valid records
  only, CRC-checked), truncates, fsyncs, deletes it — the database returns to the state
  of the last committed transaction.

### 1.5 Concurrency

* One global fair `ReentrantLock` in `Database` serialises statements (serializable
  isolation, simple and correct).
* Autocommit statement: lock → (begin tx) → execute → (commit / rollback on error) → unlock.
* `BEGIN` acquires the lock and holds it until `COMMIT`/`ROLLBACK` or disconnect, so other
  sessions wait (lock timeout → `ERROR: database is locked`).
* Each client runs entirely on its own virtual thread, so ReentrantLock ownership is
  per session. `BufferPool` and `Pager` are additionally internally thread-safe.

---

## 2. Module list, file ownership and agents

**Rule: every file has exactly one owner. No agent edits a file it does not own.**

| Owner | Files (under `src/main/java/com/minidb/` unless noted) | Responsibility |
|---|---|---|
| **Main agent** (contracts + glue) | `DbException.java`, `Database.java`, `Main.java`, `storage/Page.java`, `table/Row.java`, `query/Statement.java`, `query/QueryResult.java`, `src/test/java/com/minidb/testkit/MiniTest.java`, `pom.xml`, `run.sh`, `test.sh`, `README.md`, `.gitignore`, `PLAN.md` | Shared types, engine facade, entry point, build, integration tests, docs |
| **Subagent-1** Storage | `storage/Pager.java`, `src/test/java/com/minidb/storage/PagerTest.java` | File as an array of 4096-byte pages |
| **Subagent-2** Index | `index/BPlusTree.java`, `index/BPlusTreeNode.java`, `src/test/java/com/minidb/index/BPlusTreeTest.java` | Disk B+ tree: insert / search / update / delete / range |
| **Subagent-3** Cache | `cache/BufferPool.java`, `src/test/java/com/minidb/cache/BufferPoolTest.java` | 100-frame cache, Clock eviction, pins, dirty write-back, journaling hook |
| **Subagent-4** Table | `table/Table.java`, `table/Catalog.java`, `src/test/java/com/minidb/table/TableTest.java` | Catalog page, heap pages, row CRUD via the B+ tree |
| **Subagent-5** Parser | `query/SqlParser.java`, `src/test/java/com/minidb/query/SqlParserTest.java` | Tokenizer + recursive-descent parser → `Statement` |
| **Subagent-6** Transaction | `transaction/Journal.java`, `src/test/java/com/minidb/transaction/JournalTest.java` | Rollback journal, rollback, crash recovery |
| **Subagent-7** Server | `server/DbServer.java`, `server/DbClient.java`, `src/test/java/com/minidb/server/DbServerTest.java` | TCP server (virtual threads), client CLI + load tester |
| **Subagent-8** Executor | `query/QueryExecutor.java`, `src/test/java/com/minidb/query/QueryExecutorTest.java` | Session, transaction control, statement execution |
| Fix-subagents (on demand) | exactly one file each | Repair a file that fails integration tests |
| Review subagent (end) | read-only | Independent review of the integrated code |

### 2.1 How a subagent works (mandatory)

1. Read this PLAN.md (your section + §1 + §4) and the contract skeletons of your own files
   and of every file you depend on. Do **not** change any public signature of the
   contract. You may add private/package-private members and private nested classes.
2. Write your implementation **directly into your own files** in
   `/home/claude/minidb-from-scratch`. Never touch other files there.
3. Test in an isolated scratch dir `/home/claude/scratch/<module>/`:
   copy the pristine contract snapshot from `/home/claude/contracts/` into it, overlay
   your own files, and replace the skeletons of your dependencies **in the scratch copy
   only** with minimal fakes that honour the contract. Compile with
   `javac --release 21 -d out $(find src -name '*.java')` and run your test class.
   (Other agents are editing the real tree concurrently — do not compile the real tree.)
4. Your test class must use only `com.minidb.testkit.MiniTest`, create files only under
   `Files.createTempDirectory(...)`, and must pass both against your fakes and (later)
   against the real modules — so use only contract APIs of dependencies.
5. Quality bar: JavaDoc on every public type/method/constant, no `TODO`/`FIXME`, no
   hardcoded paths, no `System.exit` outside `main`, no external libraries, Java 21.
6. Return: a short report (what you built, design decisions, test results, any contract
   ambiguity you resolved and how).

---

## 3. Interface contracts (semantics)

Exceptions: all engine errors are `com.minidb.DbException` (unchecked). `IOException`s
are wrapped in `DbException`. Programming errors (bad arguments, pin underflow) may use
`IllegalArgumentException` / `IllegalStateException`.

### 3.1 `storage.Page` (main agent — implemented)
4096-byte buffer + page number. Big-endian typed accessors (`getInt/putInt`, `getLong`,
`getShort`, `getByte`, `getBytes/putBytes`, `fill`, `copyFrom`, `copyData`).
`Page implements AutoCloseable`: `close()` returns the pin to whoever handed the page out
(the BufferPool sets a `Page.Releaser`). **Every page obtained from the BufferPool must be
closed exactly once** (use try-with-resources). Pages from the Pager directly have no
releaser; closing them is a no-op.

### 3.2 `storage.Pager` (Subagent-1)
* `Pager(Path file)` opens or creates the file. Page count = `size / 4096`; a partial
  trailing page (crash while extending) is truncated away on open.
* `getPageCount()`, `getPage(n)` (reads from disk, fresh `Page` every call; `DbException`
  if `n` out of range), `flushPage(Page)` (writes at `n*4096`; `n` must be `< pageCount`),
  `createNewPage()` (appends a zero page **to the file**, increments count, returns it),
  `truncate(count)` (`0 <= count <= pageCount`), `sync()` (`FileChannel.force(true)`),
  `getPath()`, `close()`. Thread-safe. Uses positional `FileChannel` I/O with full-buffer
  loops.

### 3.3 `transaction.Journal` (Subagent-6)
File format:
```
header : magic "MDBJRNL1" (8) | originalPageCount int | pageSize int (4096) | crc32 of previous 16 bytes (int)
record : pageNum int | 4096 bytes before-image | crc32 int over (pageNum bytes + image)
```
* `begin(originalPageCount)` — `DbException` if already active. No file I/O yet.
* `recordOriginal(n, bytes)` — no-op if inactive, if `n >= originalPageCount`, or if `n`
  already journaled; otherwise creates the file + header on first use and appends a
  record (copying `bytes`). Not fsynced here.
* `sync()` — fsync the journal; if active and no file exists yet, first create it with the
  header (so pages appended during the transaction are truncated by recovery).
* Memory is bounded: only the set of journaled page numbers is kept in memory; rollback
  reads before-images back from the file and recovery streams records.
* After creating or deleting the journal the parent directory is fsynced (best effort).
* `commit()` — close and delete the journal file, become inactive.
* `rollback(pager)` — write every before-image back via `pager.flushPage`, then
  `pager.truncate(originalPageCount)` (only if the pager grew), `pager.sync()`, delete the
  journal, become inactive. No-op-safe if nothing was journaled.
* `static recover(path, pager)` — hot-journal recovery at startup (see §1.4). Stops at the
  first truncated or CRC-mismatched record. If the header is missing/invalid the file is
  simply deleted (it was never synced, so the db was never touched). Returns `true` iff
  pages were restored.
* `isActive()`, `isJournaled(n)`, `getOriginalPageCount()`, `getPath()`, `close()` (closes
  the handle; leaves a file of an active transaction in place for recovery).

### 3.4 `cache.BufferPool` (Subagent-3)
* `BufferPool(pager, journal /* nullable */, capacity)`; `DEFAULT_CAPACITY = 100`.
* Frames: page, pin count, ref bit, dirty bit. Lookup `pageNum → frame` via hash map.
* `getPage(n)` — returns the cached `Page` (loads via `pager.getPage` on miss), pin+1, ref
  bit = 1. Same `Page` instance for every caller while cached.
* `getPageForWrite(n)` — as `getPage`, plus: if journal active and not yet journaled,
  `journal.recordOriginal(n, page.getData())` **before** returning; mark dirty.
* `createNewPage()` — `pager.createNewPage()`, cache it pinned + dirty + ref.
* Page close → `Releaser` → pin−1 (`IllegalStateException` on underflow).
* Eviction = **Clock**: sweep from the hand; skip pinned frames; ref=1 → clear, advance;
  ref=0 → victim. If the victim is dirty: `journal.sync()` (if journal active), then
  `pager.flushPage`. If every frame is pinned → `DbException("buffer pool exhausted…")`.
* `flushPage(n)`, `flushAll()` (journal sync if active & any dirty → write all dirty →
  `pager.sync()`), `discardAll()` (drop every frame without writing;
  `IllegalStateException` if any frame is pinned), `discardAllForced()` (drops every frame even if
  pinned, detaches releasers, returns the number of leaked pins — used by ROLLBACK so a leaked
  pin can never block recovery), `getPageCount()`, `getCapacity()`,
  `getCachedPageCount()`, `getPinnedCount()`, `getStats()` (hits, misses, evictions,
  dirty write-backs), `close()` (= `flushAll`, does not close the pager).
* Thread-safe (internal `ReentrantLock`; never `synchronized` around I/O).

### 3.5 `index.BPlusTree` / `index.BPlusTreeNode` (Subagent-2)
* Keys `int`, values `long` (a RID). Unique keys.
* One node = one page. Suggested layout (Subagent-2 owns the exact layout):
  leaf: `type=1 | keyCount short | nextLeaf int (-1 none) | entries[key int, value long]`;
  internal: `type=2 | keyCount short | children[keyCount+1] int | keys[keyCount] int`.
  Physical maxima: `MAX_LEAF_KEYS` / `MAX_INTERNAL_KEYS` derived from 4096 bytes.
* `static create(pool)` allocates an empty root leaf and returns its page number.
* **The root page number never changes**: on root split, the root's contents move to two
  newly allocated pages and the root becomes an internal node with one key. Hence the
  catalog never needs updating after splits.
* Leaf split: right half moves to a new leaf, leaf chain relinked, first key of the right
  leaf is copied up. Internal split: the median key moves up.
* `insert` (`DbException` on duplicate), `search` (`OptionalLong`), `update`, `delete`
  (lazy: removes the entry from its leaf, no merge/borrow; empty leaves stay linked and
  are skipped by scans — documented trade-off), `rangeQuery(lo, hi)` (inclusive, ascending,
  follows leaf links), `height()`, `count()`.
* A test constructor accepts smaller `maxLeafKeys` / `maxInternalKeys` to exercise splits.
* Must read pages with `getPage`, and call `getPageForWrite` **before** modifying a page;
  every page is closed (try-with-resources). At most ~2×height pages pinned at once.

### 3.6 `table.Row` (main agent — implemented)
`record Row(int id, String name)`; validates name ≤ 255 UTF-8 bytes; `writeTo(page, off)`,
`readFrom(page, off)`, `SIZE = 260`.

### 3.7 `table.Catalog` / `table.Table` (Subagent-4)
* Page 0 layout (Subagent-4 owns exact layout): magic `MINIDB01`, version, table count,
  then fixed-size table entries: name, key column, value column (≤ 32 UTF-8 bytes each),
  B+ tree root page, first heap page, last heap page, row count.
* `Catalog.initialize(pool)` — if `pool.getPageCount()==0` create page 0 (must come back as
  page number 0) and write an empty catalog; otherwise validate the magic
  (`DbException("not a minidb database file")`).
* `createTable(name, keyCol, valueCol)` — case-insensitive uniqueness, creates the B+ tree
  root; `getTable(name)` (case-insensitive; `DbException("no such table: x")`),
  `hasTable`, `listTables` (creation order).
* `Table` holds only immutable facts (name, columns, root page, catalog slot). Mutable
  metadata (last heap page, row count) is **always read/written through page 0 via the
  pool**, never cached.
* Heap page: `type=3`, slot bitmap, next-heap pointer, free-list pointer + flag, 15 slots.
  Each table keeps a **free list** of heap pages that have at least one free slot (head
  stored in its catalog entry), so slots freed anywhere are reused.
  RID = `((long) pageNum << 16) | slot`.
* `insert(row)` — validates everything (duplicate id via the index) **before** touching
  any page; places the row in the free-list head page, else allocates, links and pushes a
  new heap page; inserts `id → RID` into the B+ tree; row count +1.
* `find(id)`, `scan(lo, hi)` (inclusive, ascending by id via `rangeQuery`), `delete(id)`
  (index delete + free slot + count−1), `update(id, newName)` (in place), `count()`.

### 3.8 `query.Statement` (main agent — implemented)
Sealed AST: `CreateTable`, `Insert(table, tuples)`, `Select(table, columns /* empty = * */, where)`,
`Delete(table, where)`, `Update(table, column, value, where)`, `Begin`, `Commit`,
`Rollback`, `ShowTables`. `where` = list of `Comparison(column, Op, value)` joined by AND;
`value` is `Long` or `String`. `Op ∈ {EQ, LT, LE, GT, GE}`.

### 3.9 `query.SqlParser` (Subagent-5)
`static Statement parse(String sql)`; `DbException("syntax error: …")` on bad input.
Case-insensitive keywords, identifiers `[A-Za-z_][A-Za-z0-9_]*`, integers (optional
leading `-`, parsed as `long`), single-quoted strings with `''` escape, optional trailing
`;`, arbitrary whitespace.
```
CREATE TABLE t '(' col (INT|INTEGER) ',' col (VARCHAR|TEXT) ['(' n ')'] ')'      n in 1..255
INSERT INTO t VALUES '(' int ',' string ')' (',' '(' int ',' string ')')*
SELECT ('*' | col (',' col)*) FROM t [WHERE cond]
DELETE FROM t [WHERE cond]
UPDATE t SET col '=' string [WHERE cond]
BEGIN [TRANSACTION] | START TRANSACTION | COMMIT [TRANSACTION] | ROLLBACK [TRANSACTION]
SHOW TABLES
cond := pred (AND pred)* ;  pred := col op literal | col BETWEEN int AND int (→ GE + LE)
op   := = | < | > | <= | >=
```

### 3.10 `query.QueryResult` (main agent — implemented)
`ok(message)`, `error(message)`, `rows(columns, rows)`; `render()` produces:
```
OK: 1 row inserted                      ERROR: no such table: foo
+-----+-------+
| id  | name  |
+-----+-------+
| 1   | Jewel |
+-----+-------+
(1 row)
```

### 3.11 `query.QueryExecutor` (Subagent-8)
One instance per session (connection). `execute(sql)` never throws for user errors: it
returns `QueryResult.error(...)`.
* Messages: `OK: table <t> created`, `OK: <n> row(s) inserted|deleted|updated`
  (`1 row` / `N rows`), `OK: transaction started|committed|rolled back`.
* WHERE folding: comparisons on the key column fold into one inclusive `long` range
  (`GT v → lo=v+1`, `LT v → hi=v−1`, …), clamped to `int`; empty range → no rows; equal
  bounds → `table.find`. Comparisons on the value column filter rows (String
  `compareTo`). Type mismatch / unknown column → error.
* Autocommit for writes (lock, begin, run, commit; any exception → rollback). Reads in
  autocommit just take the lock. `BEGIN` takes the lock (`tryLock` with timeout) and holds
  it; COMMIT / ROLLBACK release it. Errors: `transaction already active`,
  `no active transaction`, `database is locked`.
* Statement-level validation before mutation (e.g. multi-row INSERT checks all ids, incl.
  duplicates inside the statement, before inserting any).
* Every write has a **validation phase** and a **mutation phase**. Inside an explicit
  transaction a validation error leaves the transaction open; any failure during mutation
  (or any `Error`) rolls the whole transaction back and says so. In autocommit, any
  `Throwable` before a successful commit triggers a rollback.
* `close()` rolls back an open transaction and releases the lock (must be called on the
  session's own thread).

### 3.12 `Database` (main agent — implemented)
`open(dataDir, name, cacheCapacity)`: create dir → `Pager` → `Journal.recover` → `Journal`
→ `BufferPool` → `Catalog.initialize` → flush. Exposes `catalog()`, `bufferPool()`,
`tryLock(Duration)`, `unlock()`, `beginTransaction()`, `commitTransaction()`,
`rollbackTransaction()`, `isTransactionActive()`, `close()`.

### 3.13 `server.DbServer` / `server.DbClient` (Subagent-7)
Wire protocol (text, UTF-8, `\n` line endings, `\r\n` tolerated):
* request = one line containing one SQL statement (max 64 KiB);
* response = `QueryResult.render()` lines, then a terminator line `END`;
* `QUIT` / `EXIT` → `OK: bye`, `END`, connection closed; blank line → `ERROR: empty statement`.

`DbServer(db, port)` (`0` = ephemeral), `start()` (non-blocking; accept loop on its own
thread; each client on `Thread.ofVirtual()`), `getPort()`, `getActiveClientCount()`,
`awaitStop()`, `stop()` / `close()` (close listener + all client sockets, sessions roll
back on their own threads).

`DbClient(host, port)`, `execute(sql) → String` (response without `END`), `close()`, and a
CLI `main`: `--host`, `--port`, `--wait <sec>` (retry connect), `-e <sql>`, `--time`
(prints `time_ms=<ms>` for the `-e` statement, measured on an open connection),
`--file <path|->` (one statement per line, skips blanks and `--` comments, `--quiet`,
prints `executed=N ok=N errors=N`), `--bench <clients> <queries>` with `--bench-table`
and `--bench-base` (each client alternates INSERT of a unique id and SELECT of that id,
verifying the row; prints `clients= queries= errors= elapsed_ms= qps=`), interactive
REPL otherwise. Exit code 1 on any `ERROR` response or failure.

---

## 4. Testing strategy

1. **Unit tests per module** (`src/test/java/**/XxxTest.java`, plain `main` + `MiniTest`,
   no JUnit): written by each subagent, run against fakes in scratch, then against the
   real modules during integration.
2. **Integration tests** — `test.sh` builds with `javac` (no network needed), runs every
   unit test, starts the server on port 6969 with a fresh temp data dir, and checks:
   1. `CREATE TABLE users` → `OK`
   2. INSERT 1000 rows → all inserted (`ok=1000 errors=0`, `count` via full scan)
   3. `SELECT … WHERE id = 500` → correct row, round-trip `< 10 ms`
   4. `WHERE id > 100 AND id < 200` → `(99 rows)`
   5. `DELETE id=10` then `SELECT id=10` → `(0 rows)`
   6. `BEGIN; INSERT; ROLLBACK` → row absent
   7. `BEGIN; INSERT; COMMIT` → row present
   8. Restart server (hard `kill -9`, no clean shutdown) → data still there
   9. 10 parallel clients × 100 queries → `errors=0`
3. **Independent review** by a fresh agent that did not write the code.

## 5. Delivery checklist
JavaDoc on every public member · no TODO · no hardcoded paths (data dir / port / cache
size via CLI flags or `MINIDB_*` env vars) · `.gitignore` (`target/`, `*.class`, `*.db`,
`*.journal`) · README with diagram, how to run, 10 SQL examples, what I learned.
