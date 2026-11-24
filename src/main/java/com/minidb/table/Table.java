package com.minidb.table;

import com.minidb.DbException;
import com.minidb.cache.BufferPool;
import com.minidb.index.BPlusTree;
import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A table: rows in heap pages plus a B+ tree primary index on the key column.
 * Instances are created only by {@link Catalog}.
 *
 * <p>The table holds only immutable facts (name, columns, index root, catalog slot).
 * The mutable metadata (first/last heap page, row count) always lives in page 0 and is
 * read and written through the buffer pool, never cached in a field.</p>
 *
 * <h2>Heap page layout (big-endian)</h2>
 * <pre>
 * offset 0   byte   page type (= 3)
 * offset 1   short  slot bitmap, bit i set = slot i in use
 * offset 3   int    next heap page (-1 = none)
 * offset 7   int    next page in the free list (-1 = none)
 * offset 11  byte   1 if the page is in the free list, else 0
 * offset 12  {@value #SLOTS_PER_PAGE} slots of {@link Row#SIZE} bytes each
 * </pre>
 * The per-table free list (head stored in the catalog entry) links exactly the heap pages
 * that have at least one free slot, so freed slots in any page are reused.
 * A record id (RID) is {@code ((long) pageNum << 16) | slot}.
 */
public final class Table {

    /** Page type byte of heap pages. */
    static final byte HEAP_PAGE_TYPE = 3;

    /** Number of row slots per heap page. */
    static final int SLOTS_PER_PAGE = 15;

    private static final int BITMAP_OFFSET = 1;
    private static final int NEXT_OFFSET = 3;
    private static final int NEXT_FREE_OFFSET = 7;
    private static final int IN_FREE_LIST_OFFSET = 11;
    private static final int FULL_BITMAP = (1 << SLOTS_PER_PAGE) - 1;
    private static final int SLOTS_OFFSET = 12;
    private static final long SLOT_MASK = 0xFFFFL;

    private final BufferPool pool;
    private final int catalogSlot;
    private final String name;
    private final String keyColumn;
    private final String valueColumn;
    private final BPlusTree tree;

    /**
     * Package-private: obtain tables from {@link Catalog}.
     *
     * @param pool        buffer pool
     * @param catalogSlot index of this table's entry in the catalog page
     * @param name        table name
     * @param keyColumn   key column name
     * @param valueColumn value column name
     * @param rootPage    root page of the primary-key B+ tree
     */
    Table(BufferPool pool, int catalogSlot, String name, String keyColumn, String valueColumn, int rootPage) {
        this.pool = pool;
        this.catalogSlot = catalogSlot;
        this.name = name;
        this.keyColumn = keyColumn;
        this.valueColumn = valueColumn;
        this.tree = new BPlusTree(pool, rootPage);
    }

    /**
     * Returns the table name as created.
     *
     * @return table name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the INT key column name.
     *
     * @return key column name
     */
    public String getKeyColumn() {
        return keyColumn;
    }

    /**
     * Returns the VARCHAR column name.
     *
     * @return value column name
     */
    public String getValueColumn() {
        return valueColumn;
    }

    /**
     * Inserts a row. All validation happens before any page is modified.
     *
     * @param row the row
     * @throws com.minidb.DbException {@code "duplicate key: <id>"} if the id exists
     */
    public void insert(Row row) {
        if (tree.search(row.id()).isPresent()) {
            throw new DbException("duplicate key: " + row.id());
        }
        int pageNum = readMeta(Catalog.FREE_HEAD_FIELD);
        int slot = pageNum >= 0 ? findFreeSlot(pageNum) : -1;
        if (slot < 0) {
            pageNum = appendHeapPage(readMeta(Catalog.LAST_HEAP_FIELD));
            slot = 0;
        }
        int newHead = -2;
        try (Page page = pool.getPageForWrite(pageNum)) {
            row.writeTo(page, slotOffset(slot));
            int bitmap = page.getShort(BITMAP_OFFSET) | (1 << slot);
            page.putShort(BITMAP_OFFSET, (short) bitmap);
            if (bitmap == FULL_BITMAP) {
                newHead = page.getInt(NEXT_FREE_OFFSET);
                page.putInt(NEXT_FREE_OFFSET, -1);
                page.putByte(IN_FREE_LIST_OFFSET, (byte) 0);
            }
        }
        if (newHead != -2) {
            writeMeta(Catalog.FREE_HEAD_FIELD, newHead);
        }
        tree.insert(row.id(), makeRid(pageNum, slot));
        adjustRowCount(1);
    }

    /**
     * Point lookup through the index.
     *
     * @param id key value
     * @return the row, if present
     */
    public Optional<Row> find(int id) {
        OptionalLong rid = tree.search(id);
        if (rid.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(readRow(rid.getAsLong()));
    }

    /**
     * Range scan through the index.
     *
     * @param lowInclusive  lower bound
     * @param highInclusive upper bound
     * @return rows with {@code low <= id <= high}, ascending by id
     */
    public List<Row> scan(int lowInclusive, int highInclusive) {
        List<BPlusTree.Entry> entries = tree.rangeQuery(lowInclusive, highInclusive);
        List<Row> rows = new ArrayList<>(entries.size());
        int i = 0;
        while (i < entries.size()) {
            int pageNum = pageOf(entries.get(i).value());
            try (Page page = pool.getPage(pageNum)) {
                while (i < entries.size() && pageOf(entries.get(i).value()) == pageNum) {
                    rows.add(Row.readFrom(page, slotOffset(slotOf(entries.get(i).value()))));
                    i++;
                }
            }
        }
        return rows;
    }

    /**
     * Deletes a row.
     *
     * @param id key value
     * @return {@code true} if a row was deleted
     */
    public boolean delete(int id) {
        OptionalLong found = tree.search(id);
        if (found.isEmpty()) {
            return false;
        }
        long rid = found.getAsLong();
        int pageNum = pageOf(rid);
        int head = readMeta(Catalog.FREE_HEAD_FIELD);
        boolean pushed = false;
        try (Page page = pool.getPageForWrite(pageNum)) {
            int bitmap = page.getShort(BITMAP_OFFSET);
            page.putShort(BITMAP_OFFSET, (short) (bitmap & ~(1 << slotOf(rid))));
            if (page.getByte(IN_FREE_LIST_OFFSET) == 0) {
                page.putInt(NEXT_FREE_OFFSET, head);
                page.putByte(IN_FREE_LIST_OFFSET, (byte) 1);
                pushed = true;
            }
        }
        if (pushed) {
            writeMeta(Catalog.FREE_HEAD_FIELD, pageNum);
        }
        tree.delete(id);
        adjustRowCount(-1);
        return true;
    }

    /**
     * Replaces the value column of a row in place.
     *
     * @param id      key value
     * @param newName new value (validated before modifying anything)
     * @return {@code true} if the row existed
     */
    public boolean update(int id, String newName) {
        Row replacement = new Row(id, newName);
        OptionalLong found = tree.search(id);
        if (found.isEmpty()) {
            return false;
        }
        long rid = found.getAsLong();
        try (Page page = pool.getPageForWrite(pageOf(rid))) {
            replacement.writeTo(page, slotOffset(slotOf(rid)));
        }
        return true;
    }

    /**
     * Returns the number of rows (from the catalog).
     *
     * @return row count
     */
    public long count() {
        try (Page page = pool.getPage(Catalog.CATALOG_PAGE)) {
            return page.getLong(metaOffset(Catalog.ROW_COUNT_FIELD));
        }
    }

    private Row readRow(long rid) {
        try (Page page = pool.getPage(pageOf(rid))) {
            return Row.readFrom(page, slotOffset(slotOf(rid)));
        }
    }

    /** Returns the lowest free slot of a heap page, or -1 if it is full. */
    private int findFreeSlot(int pageNum) {
        try (Page page = pool.getPage(pageNum)) {
            int bitmap = page.getShort(BITMAP_OFFSET);
            for (int slot = 0; slot < SLOTS_PER_PAGE; slot++) {
                if ((bitmap & (1 << slot)) == 0) {
                    return slot;
                }
            }
        }
        return -1;
    }

    /**
     * Allocates and formats a heap page, links it after {@code previousLast} and pushes it on
     * the free list (only called when the free list is empty).
     */
    private int appendHeapPage(int previousLast) {
        int pageNum;
        try (Page page = pool.createNewPage()) {
            pageNum = page.getPageNum();
            page.fill(0, Page.SIZE, (byte) 0);
            page.putByte(0, HEAP_PAGE_TYPE);
            page.putShort(BITMAP_OFFSET, (short) 0);
            page.putInt(NEXT_OFFSET, -1);
            page.putInt(NEXT_FREE_OFFSET, -1);
            page.putByte(IN_FREE_LIST_OFFSET, (byte) 1);
        }
        if (previousLast >= 0) {
            try (Page prev = pool.getPageForWrite(previousLast)) {
                prev.putInt(NEXT_OFFSET, pageNum);
            }
        }
        try (Page meta = pool.getPageForWrite(Catalog.CATALOG_PAGE)) {
            if (previousLast < 0) {
                meta.putInt(metaOffset(Catalog.FIRST_HEAP_FIELD), pageNum);
            }
            meta.putInt(metaOffset(Catalog.LAST_HEAP_FIELD), pageNum);
            meta.putInt(metaOffset(Catalog.FREE_HEAD_FIELD), pageNum);
        }
        return pageNum;
    }

    private void adjustRowCount(long delta) {
        try (Page meta = pool.getPageForWrite(Catalog.CATALOG_PAGE)) {
            int offset = metaOffset(Catalog.ROW_COUNT_FIELD);
            meta.putLong(offset, meta.getLong(offset) + delta);
        }
    }

    private int readMeta(int field) {
        try (Page page = pool.getPage(Catalog.CATALOG_PAGE)) {
            return page.getInt(metaOffset(field));
        }
    }

    private void writeMeta(int field, int value) {
        try (Page page = pool.getPageForWrite(Catalog.CATALOG_PAGE)) {
            page.putInt(metaOffset(field), value);
        }
    }

    private int metaOffset(int field) {
        return Catalog.entryOffset(catalogSlot) + field;
    }

    private static int slotOffset(int slot) {
        return SLOTS_OFFSET + slot * Row.SIZE;
    }

    private static long makeRid(int pageNum, int slot) {
        return ((long) pageNum << 16) | slot;
    }

    private static int pageOf(long rid) {
        return (int) (rid >>> 16);
    }

    private static int slotOf(long rid) {
        return (int) (rid & SLOT_MASK);
    }
}
