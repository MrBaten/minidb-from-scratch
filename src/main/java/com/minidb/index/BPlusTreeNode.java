package com.minidb.index;

import com.minidb.storage.Page;

/**
 * Typed view over a page holding one B+ tree node (leaf or internal). Its API is private
 * to the {@code index} package.
 *
 * <p>Page layout (all integers big-endian):</p>
 * <pre>
 * common   : byte 0 type (1 = leaf, 2 = internal) | short keyCount at offset 1
 * leaf     : int nextLeaf at offset 3 (-1 = none) | entries [key int, value long] from offset 16
 * internal : int children[MAX_INTERNAL_KEYS + 1] from offset 16 | int keys[MAX_INTERNAL_KEYS] after them
 * </pre>
 *
 * <p>The view holds no state besides the page reference; it must not be used after the page
 * has been closed. Mutating methods require that the page was obtained for writing.</p>
 */
final class BPlusTreeNode {

    /** Page type byte of a leaf. */
    static final byte TYPE_LEAF = 1;

    /** Page type byte of an internal node. */
    static final byte TYPE_INTERNAL = 2;

    /** Value of the next-leaf pointer meaning "no next leaf". */
    static final int NO_PAGE = -1;

    /** Size of the node header in bytes. */
    private static final int HEADER_SIZE = 16;

    private static final int OFF_TYPE = 0;
    private static final int OFF_COUNT = 1;
    private static final int OFF_NEXT = 3;
    private static final int LEAF_ENTRY_SIZE = 12;

    /** Maximum number of entries a leaf page can physically hold. */
    static final int MAX_LEAF_KEYS = (Page.SIZE - HEADER_SIZE) / LEAF_ENTRY_SIZE;

    /** Maximum number of keys an internal page can physically hold. */
    static final int MAX_INTERNAL_KEYS = (Page.SIZE - HEADER_SIZE - 4) / 8;

    private static final int CHILDREN_OFFSET = HEADER_SIZE;
    private static final int KEYS_OFFSET = CHILDREN_OFFSET + 4 * (MAX_INTERNAL_KEYS + 1);

    private final Page page;

    /**
     * Wraps a page.
     *
     * @param page the page holding the node
     */
    BPlusTreeNode(Page page) {
        this.page = page;
    }

    /**
     * Tells whether a page type byte denotes a B+ tree node.
     *
     * @param type the type byte
     * @return {@code true} for leaf or internal
     */
    static boolean isNodeType(byte type) {
        return type == TYPE_LEAF || type == TYPE_INTERNAL;
    }

    /**
     * Returns the wrapped page.
     *
     * @return the page
     */
    Page page() {
        return page;
    }

    /**
     * Returns the type byte of the page.
     *
     * @return {@link #TYPE_LEAF}, {@link #TYPE_INTERNAL} or another value for foreign pages
     */
    byte type() {
        return page.getByte(OFF_TYPE);
    }

    /**
     * Tells whether this node is a leaf.
     *
     * @return {@code true} for a leaf
     */
    boolean isLeaf() {
        return type() == TYPE_LEAF;
    }

    /**
     * Formats the page as an empty leaf without next leaf.
     */
    void initLeaf() {
        page.fill(0, Page.SIZE, (byte) 0);
        page.putByte(OFF_TYPE, TYPE_LEAF);
        setNext(NO_PAGE);
    }

    /**
     * Formats the page as an internal node with no keys (the caller fills it with
     * {@link #setInternalEntries(int[], int[], int, int)}).
     */
    void initInternal() {
        page.fill(0, Page.SIZE, (byte) 0);
        page.putByte(OFF_TYPE, TYPE_INTERNAL);
    }

    /**
     * Returns the number of keys (leaf entries or internal separator keys).
     *
     * @return key count
     */
    int keyCount() {
        return page.getShort(OFF_COUNT);
    }

    /**
     * Sets the number of keys.
     *
     * @param count the new key count
     */
    void setKeyCount(int count) {
        page.putShort(OFF_COUNT, (short) count);
    }

    /**
     * Returns the page number of the next leaf in the chain.
     *
     * @return next leaf page number or {@link #NO_PAGE}
     */
    int next() {
        return page.getInt(OFF_NEXT);
    }

    /**
     * Sets the next-leaf pointer.
     *
     * @param pageNum next leaf page number or {@link #NO_PAGE}
     */
    void setNext(int pageNum) {
        page.putInt(OFF_NEXT, pageNum);
    }

    /**
     * Returns the key at an index (leaf entry key or internal separator).
     *
     * @param index position, {@code 0 <= index < keyCount()}
     * @return the key
     */
    int key(int index) {
        return isLeaf() ? page.getInt(leafOffset(index)) : page.getInt(KEYS_OFFSET + 4 * index);
    }

    private void setKey(int index, int key) {
        page.putInt(KEYS_OFFSET + 4 * index, key);
    }

    /**
     * Returns the value of a leaf entry.
     *
     * @param index position of the entry
     * @return the value
     */
    long value(int index) {
        return page.getLong(leafOffset(index) + 4);
    }

    /**
     * Overwrites the value of a leaf entry.
     *
     * @param index position of the entry
     * @param value the new value
     */
    void setValue(int index, long value) {
        page.putLong(leafOffset(index) + 4, value);
    }

    /**
     * Returns a child pointer of an internal node.
     *
     * @param index position, {@code 0 <= index <= keyCount()}
     * @return child page number
     */
    int child(int index) {
        return page.getInt(CHILDREN_OFFSET + 4 * index);
    }

    private void setChild(int index, int pageNum) {
        page.putInt(CHILDREN_OFFSET + 4 * index, pageNum);
    }

    private static int leafOffset(int index) {
        return HEADER_SIZE + LEAF_ENTRY_SIZE * index;
    }

    /**
     * Binary-searches a leaf for a key.
     *
     * @param key the key to find
     * @return the index of the key if present, otherwise {@code -(insertionPoint) - 1}
     */
    int findInLeaf(int key) {
        int lo = 0;
        int hi = keyCount() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int k = page.getInt(leafOffset(mid));
            if (k < key) {
                lo = mid + 1;
            } else if (k > key) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /**
     * Binary-searches an internal node for the child that may contain a key: the number
     * of separator keys that are less than or equal to {@code key}.
     *
     * @param key the key
     * @return child index, {@code 0 <= result <= keyCount()}
     */
    int childIndexFor(int key) {
        int lo = 0;
        int hi = keyCount();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (page.getInt(KEYS_OFFSET + 4 * mid) <= key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Inserts an entry into a leaf with spare room, shifting later entries right.
     *
     * @param index position to insert at
     * @param key   the key
     * @param value the value
     */
    void insertLeafEntry(int index, int key, long value) {
        int count = keyCount();
        page.move(leafOffset(index), leafOffset(index + 1), (count - index) * LEAF_ENTRY_SIZE);
        page.putInt(leafOffset(index), key);
        page.putLong(leafOffset(index) + 4, value);
        setKeyCount(count + 1);
    }

    /**
     * Removes an entry from a leaf, shifting later entries left.
     *
     * @param index position of the entry
     */
    void removeLeafEntry(int index) {
        int count = keyCount();
        page.move(leafOffset(index + 1), leafOffset(index), (count - index - 1) * LEAF_ENTRY_SIZE);
        setKeyCount(count - 1);
    }

    /**
     * Replaces all entries of a leaf.
     *
     * @param keys   keys array
     * @param values values array
     * @param from   first source index (inclusive)
     * @param to     last source index (exclusive)
     */
    void setLeafEntries(int[] keys, long[] values, int from, int to) {
        for (int i = from; i < to; i++) {
            page.putInt(leafOffset(i - from), keys[i]);
            page.putLong(leafOffset(i - from) + 4, values[i]);
        }
        setKeyCount(to - from);
    }

    /**
     * Inserts a separator key and the child to its right into an internal node with
     * spare room.
     *
     * @param index      position of the new key; the new child goes to {@code index + 1}
     * @param key        the separator key
     * @param rightChild page number of the child right of the key
     */
    void insertSeparator(int index, int key, int rightChild) {
        int count = keyCount();
        page.move(KEYS_OFFSET + 4 * index, KEYS_OFFSET + 4 * (index + 1), (count - index) * 4);
        page.move(CHILDREN_OFFSET + 4 * (index + 1), CHILDREN_OFFSET + 4 * (index + 2), (count - index) * 4);
        setKey(index, key);
        setChild(index + 1, rightChild);
        setKeyCount(count + 1);
    }

    /**
     * Replaces the contents of an internal node.
     *
     * @param keys     separator keys array
     * @param children child array (one more element than the key range used)
     * @param from     first key index (inclusive); the children start at the same index
     * @param to       last key index (exclusive); the children end at {@code to} inclusive
     */
    void setInternalEntries(int[] keys, int[] children, int from, int to) {
        for (int i = from; i < to; i++) {
            setKey(i - from, keys[i]);
        }
        for (int i = from; i <= to; i++) {
            setChild(i - from, children[i]);
        }
        setKeyCount(to - from);
    }
}
