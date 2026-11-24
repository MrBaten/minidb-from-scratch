package com.minidb.table;

import com.minidb.DbException;
import com.minidb.cache.BufferPool;
import com.minidb.index.BPlusTree;
import com.minidb.storage.Page;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The system catalog stored in page 0: the list of tables and their metadata.
 *
 * <p>The catalog keeps <em>no</em> in-memory copy of the table list: every call reads
 * page 0 through the buffer pool, so a rollback (which discards the pool and restores
 * pages) is reflected automatically.</p>
 *
 * <h2>Page 0 layout (big-endian)</h2>
 * <pre>
 * offset 0    8 bytes  magic "MINIDB01" (ASCII)
 * offset 8    int      format version (= 1)
 * offset 12   int      tableCount
 * offset 16   entries, 128 bytes each, entry i at 16 + 128*i:
 *     +0     1 byte length + 32 bytes UTF-8   table name
 *     +33    1 byte length + 32 bytes UTF-8   key column name
 *     +66    1 byte length + 32 bytes UTF-8   value column name
 *     +100   int   B+ tree root page
 *     +104   int   first heap page (-1 = none)
 *     +108   int   last heap page  (-1 = none)
 *     +112   long  row count
 *     +120   int   free-list head: first heap page with a free slot (-1 = none)
 *     +124   4 bytes unused
 * </pre>
 * Up to {@link #MAX_TABLES} tables fit in the page.
 */
public final class Catalog {

    /** Page number of the catalog page. */
    public static final int CATALOG_PAGE = 0;

    /** Magic bytes at the start of page 0. */
    public static final String MAGIC = "MINIDB01";

    /** Maximum UTF-8 length of table and column names. */
    public static final int MAX_NAME_BYTES = 32;

    /** Maximum number of tables the catalog page can hold. */
    public static final int MAX_TABLES = (Page.SIZE - 16) / 128;

    private static final int FORMAT_VERSION = 1;
    private static final int VERSION_OFFSET = 8;
    private static final int COUNT_OFFSET = 12;
    private static final int ENTRIES_OFFSET = 16;
    private static final int ENTRY_SIZE = 128;
    private static final int NAME_FIELD_SIZE = 1 + MAX_NAME_BYTES;
    private static final int KEY_OFFSET = NAME_FIELD_SIZE;
    private static final int VALUE_OFFSET = 2 * NAME_FIELD_SIZE;

    /** Entry-relative offset of the B+ tree root page. */
    static final int ROOT_FIELD = 100;
    /** Entry-relative offset of the first heap page. */
    static final int FIRST_HEAP_FIELD = 104;
    /** Entry-relative offset of the last heap page. */
    static final int LAST_HEAP_FIELD = 108;
    /** Entry-relative offset of the row count. */
    static final int ROW_COUNT_FIELD = 112;
    /** Entry-relative offset of the heap free-list head. */
    static final int FREE_HEAD_FIELD = 120;

    private final BufferPool pool;

    /**
     * Creates the catalog page of a new database, or validates an existing one.
     *
     * @param pool the buffer pool
     * @throws com.minidb.DbException if page 0 is not a minidb catalog
     */
    public static void initialize(BufferPool pool) {
        if (pool.getPageCount() == 0) {
            try (Page page = pool.createNewPage()) {
                if (page.getPageNum() != CATALOG_PAGE) {
                    throw new DbException("catalog must be page 0 but got page " + page.getPageNum());
                }
                page.putBytes(0, MAGIC.getBytes(StandardCharsets.US_ASCII));
                page.putInt(VERSION_OFFSET, FORMAT_VERSION);
                page.putInt(COUNT_OFFSET, 0);
            }
            return;
        }
        try (Page page = pool.getPage(CATALOG_PAGE)) {
            byte[] magic = page.getBytes(0, MAGIC.length());
            if (!Arrays.equals(magic, MAGIC.getBytes(StandardCharsets.US_ASCII))) {
                throw new DbException("not a minidb database file");
            }
            if (page.getInt(VERSION_OFFSET) != FORMAT_VERSION) {
                throw new DbException("not a minidb database file");
            }
            int count = page.getInt(COUNT_OFFSET);
            if (count < 0 || count > MAX_TABLES) {
                throw new DbException("corrupt catalog: invalid table count " + count);
            }
        }
    }

    /**
     * Creates a catalog view.
     *
     * @param pool the buffer pool
     */
    public Catalog(BufferPool pool) {
        this.pool = pool;
    }

    /**
     * Creates a table.
     *
     * @param name        table name (unique, case-insensitive)
     * @param keyColumn   name of the INT key column
     * @param valueColumn name of the VARCHAR column
     * @return the new table
     * @throws com.minidb.DbException if it exists, a name is invalid/too long, or the catalog is full
     */
    public Table createTable(String name, String keyColumn, String valueColumn) {
        byte[] nameBytes = encodeName(name, "table name");
        byte[] keyBytes = encodeName(keyColumn, "key column name");
        byte[] valueBytes = encodeName(valueColumn, "value column name");
        if (keyColumn.toLowerCase(Locale.ROOT).equals(valueColumn.toLowerCase(Locale.ROOT))) {
            throw new DbException("key and value columns must differ: " + keyColumn);
        }
        if (hasTable(name)) {
            throw new DbException("table already exists: " + name);
        }
        int count;
        try (Page page = pool.getPage(CATALOG_PAGE)) {
            count = page.getInt(COUNT_OFFSET);
        }
        if (count >= MAX_TABLES) {
            throw new DbException("catalog is full: at most " + MAX_TABLES + " tables");
        }
        int root = BPlusTree.create(pool);
        try (Page page = pool.getPageForWrite(CATALOG_PAGE)) {
            int base = entryOffset(count);
            page.fill(base, ENTRY_SIZE, (byte) 0);
            putName(page, base, nameBytes);
            putName(page, base + KEY_OFFSET, keyBytes);
            putName(page, base + VALUE_OFFSET, valueBytes);
            page.putInt(base + ROOT_FIELD, root);
            page.putInt(base + FIRST_HEAP_FIELD, -1);
            page.putInt(base + LAST_HEAP_FIELD, -1);
            page.putLong(base + ROW_COUNT_FIELD, 0L);
            page.putInt(base + FREE_HEAD_FIELD, -1);
            page.putInt(COUNT_OFFSET, count + 1);
        }
        return new Table(pool, count, name, keyColumn, valueColumn, root);
    }

    /**
     * Looks up a table (case-insensitive).
     *
     * @param name table name
     * @return the table
     * @throws com.minidb.DbException {@code "no such table: <name>"} if absent
     */
    public Table getTable(String name) {
        String wanted = name == null ? "" : name.toLowerCase(Locale.ROOT);
        try (Page page = pool.getPage(CATALOG_PAGE)) {
            int count = page.getInt(COUNT_OFFSET);
            for (int slot = 0; slot < count; slot++) {
                int base = entryOffset(slot);
                String tableName = readName(page, base);
                if (tableName.toLowerCase(Locale.ROOT).equals(wanted)) {
                    return new Table(pool, slot, tableName,
                            readName(page, base + KEY_OFFSET),
                            readName(page, base + VALUE_OFFSET),
                            page.getInt(base + ROOT_FIELD));
                }
            }
        }
        throw new DbException("no such table: " + name);
    }

    /**
     * Tells whether a table exists (case-insensitive).
     *
     * @param name table name
     * @return {@code true} if it exists
     */
    public boolean hasTable(String name) {
        String wanted = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String existing : listTables()) {
            if (existing.toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lists table names in creation order.
     *
     * @return table names
     */
    public List<String> listTables() {
        List<String> names = new ArrayList<>();
        try (Page page = pool.getPage(CATALOG_PAGE)) {
            int count = page.getInt(COUNT_OFFSET);
            for (int slot = 0; slot < count; slot++) {
                names.add(readName(page, entryOffset(slot)));
            }
        }
        return names;
    }

    /**
     * Returns the byte offset in page 0 of the catalog entry for a table slot.
     *
     * @param slot catalog slot index
     * @return byte offset of the entry
     */
    static int entryOffset(int slot) {
        return ENTRIES_OFFSET + slot * ENTRY_SIZE;
    }

    private static byte[] encodeName(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new DbException(what + " must not be empty");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_NAME_BYTES) {
            throw new DbException(what + " too long (max " + MAX_NAME_BYTES + " bytes): " + value);
        }
        return bytes;
    }

    private static void putName(Page page, int offset, byte[] bytes) {
        page.putByte(offset, (byte) bytes.length);
        page.putBytes(offset + 1, bytes);
    }

    private static String readName(Page page, int offset) {
        int length = page.getByte(offset) & 0xFF;
        if (length > MAX_NAME_BYTES) {
            throw new DbException("corrupt catalog: name length " + length);
        }
        return new String(page.getBytes(offset + 1, length), StandardCharsets.UTF_8);
    }
}
