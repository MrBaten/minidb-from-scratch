package com.minidb.index;

import com.minidb.DbException;
import com.minidb.cache.BufferPool;
import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Disk-resident B+ tree mapping unique {@code int} keys to {@code long} values, one node
 * per page, with a fixed root page.
 *
 * <p>Design: internal nodes hold separator keys; child {@code i} contains keys {@code k}
 * with {@code keys[i-1] <= k < keys[i]}. A full leaf splits in two (the first key of the
 * right leaf is copied up and the leaf chain is relinked); a full internal node splits and
 * its median key moves up. When the root splits, its contents move into two new pages and
 * the root page is rewritten as an internal node, so the root page number never changes.
 * Deletion is lazy: the entry is removed from its leaf without merging or redistribution,
 * and empty leaves stay in the chain (scans skip them).</p>
 *
 * <p>The tree keeps no node state in memory: only the root page number and the node
 * capacities. Every page is read through {@link BufferPool#getPage(int)}, and
 * {@link BufferPool#getPageForWrite(int)} is called before any byte of a page changes.</p>
 */
public final class BPlusTree {

    /**
     * One key/value pair returned by range queries.
     *
     * @param key   the key
     * @param value the value
     */
    public record Entry(int key, long value) { }

    /** Maximum number of entries a leaf page can physically hold. */
    public static final int MAX_LEAF_KEYS = BPlusTreeNode.MAX_LEAF_KEYS;

    /** Maximum number of keys an internal page can physically hold. */
    public static final int MAX_INTERNAL_KEYS = BPlusTreeNode.MAX_INTERNAL_KEYS;

    /** Result of splitting a node: the separator to insert in the parent and the new right page. */
    private record Split(int key, int rightPage) { }

    private final BufferPool pool;
    private final int rootPageNum;
    private final int maxLeafKeys;
    private final int maxInternalKeys;

    /**
     * Allocates an empty tree (a single empty root leaf).
     *
     * @param pool the buffer pool
     * @return the root page number, to be stored by the caller
     */
    public static int create(BufferPool pool) {
        try (Page page = pool.createNewPage()) {
            new BPlusTreeNode(page).initLeaf();
            return page.getPageNum();
        }
    }

    /**
     * Opens an existing tree with physical node capacities.
     *
     * @param pool        the buffer pool
     * @param rootPageNum root page number returned by {@link #create(BufferPool)}
     * @throws DbException if the page is not a B+ tree node
     */
    public BPlusTree(BufferPool pool, int rootPageNum) {
        this(pool, rootPageNum, MAX_LEAF_KEYS, MAX_INTERNAL_KEYS);
    }

    /**
     * Opens an existing tree with reduced node capacities (for tests that exercise splits).
     *
     * @param pool            the buffer pool
     * @param rootPageNum     root page number
     * @param maxLeafKeys     entries per leaf, {@code 3 <= n <= MAX_LEAF_KEYS}
     * @param maxInternalKeys keys per internal node, {@code 3 <= n <= MAX_INTERNAL_KEYS}
     * @throws IllegalArgumentException if a capacity is out of range
     * @throws DbException              if the root page is not a B+ tree node
     */
    public BPlusTree(BufferPool pool, int rootPageNum, int maxLeafKeys, int maxInternalKeys) {
        if (maxLeafKeys < 3 || maxLeafKeys > MAX_LEAF_KEYS) {
            throw new IllegalArgumentException("maxLeafKeys must be in [3, " + MAX_LEAF_KEYS + "]: " + maxLeafKeys);
        }
        if (maxInternalKeys < 3 || maxInternalKeys > MAX_INTERNAL_KEYS) {
            throw new IllegalArgumentException(
                    "maxInternalKeys must be in [3, " + MAX_INTERNAL_KEYS + "]: " + maxInternalKeys);
        }
        this.pool = pool;
        this.rootPageNum = rootPageNum;
        this.maxLeafKeys = maxLeafKeys;
        this.maxInternalKeys = maxInternalKeys;
        try (Page page = pool.getPage(rootPageNum)) {
            if (!BPlusTreeNode.isNodeType(new BPlusTreeNode(page).type())) {
                throw new DbException("page " + rootPageNum + " is not a B+ tree page");
            }
        }
    }

    /**
     * Returns the (immutable) root page number.
     *
     * @return root page number
     */
    public int getRootPageNum() {
        return rootPageNum;
    }

    /**
     * Inserts a new key.
     *
     * @param key   the key
     * @param value the value
     * @throws DbException if the key already exists (the tree is left unchanged)
     */
    public void insert(int key, long value) {
        Split split = insertInto(rootPageNum, key, value);
        if (split != null) {
            growRoot(split);
        }
    }

    /**
     * Looks up a key.
     *
     * @param key the key
     * @return the value, or empty if absent
     */
    public OptionalLong search(int key) {
        try (Page page = pool.getPage(findLeaf(key))) {
            BPlusTreeNode leaf = new BPlusTreeNode(page);
            int index = leaf.findInLeaf(key);
            return index >= 0 ? OptionalLong.of(leaf.value(index)) : OptionalLong.empty();
        }
    }

    /**
     * Replaces the value of an existing key.
     *
     * @param key   the key
     * @param value the new value
     * @return {@code true} if the key existed
     */
    public boolean update(int key, long value) {
        int leafPage = findLeaf(key);
        if (!containsInLeaf(leafPage, key)) {
            return false;
        }
        try (Page page = pool.getPageForWrite(leafPage)) {
            BPlusTreeNode leaf = new BPlusTreeNode(page);
            leaf.setValue(leaf.findInLeaf(key), value);
            return true;
        }
    }

    /**
     * Removes a key (lazy deletion: no merge or redistribution).
     *
     * @param key the key
     * @return {@code true} if the key existed
     */
    public boolean delete(int key) {
        int leafPage = findLeaf(key);
        if (!containsInLeaf(leafPage, key)) {
            return false;
        }
        try (Page page = pool.getPageForWrite(leafPage)) {
            BPlusTreeNode leaf = new BPlusTreeNode(page);
            leaf.removeLeafEntry(leaf.findInLeaf(key));
            return true;
        }
    }

    /**
     * Returns all entries with {@code low <= key <= high} in ascending key order.
     *
     * @param lowInclusive  lower bound
     * @param highInclusive upper bound
     * @return the entries (empty if {@code low > high})
     */
    public List<Entry> rangeQuery(int lowInclusive, int highInclusive) {
        List<Entry> result = new ArrayList<>();
        if (lowInclusive > highInclusive) {
            return result;
        }
        int current = findLeaf(lowInclusive);
        while (current != BPlusTreeNode.NO_PAGE) {
            try (Page page = pool.getPage(current)) {
                BPlusTreeNode leaf = new BPlusTreeNode(page);
                int index = leaf.findInLeaf(lowInclusive);
                if (index < 0) {
                    index = -index - 1;
                }
                for (int count = leaf.keyCount(); index < count; index++) {
                    int key = leaf.key(index);
                    if (key > highInclusive) {
                        return result;
                    }
                    result.add(new Entry(key, leaf.value(index)));
                }
                current = leaf.next();
            }
        }
        return result;
    }

    /**
     * Returns the number of levels (1 for a lone root leaf).
     *
     * @return tree height
     */
    public int height() {
        int levels = 1;
        int current = rootPageNum;
        while (true) {
            try (Page page = pool.getPage(current)) {
                BPlusTreeNode node = new BPlusTreeNode(page);
                if (node.isLeaf()) {
                    return levels;
                }
                current = node.child(0);
                levels++;
            }
        }
    }

    /**
     * Counts all entries by walking the leaf chain.
     *
     * @return number of keys
     */
    public long count() {
        int current = leftmostLeaf();
        long total = 0;
        while (current != BPlusTreeNode.NO_PAGE) {
            try (Page page = pool.getPage(current)) {
                BPlusTreeNode leaf = new BPlusTreeNode(page);
                total += leaf.keyCount();
                current = leaf.next();
            }
        }
        return total;
    }

    private int leftmostLeaf() {
        int current = rootPageNum;
        while (true) {
            try (Page page = pool.getPage(current)) {
                BPlusTreeNode node = new BPlusTreeNode(page);
                if (node.isLeaf()) {
                    return current;
                }
                current = node.child(0);
            }
        }
    }

    /** Descends from the root to the leaf that would contain the key. */
    private int findLeaf(int key) {
        int current = rootPageNum;
        while (true) {
            try (Page page = pool.getPage(current)) {
                BPlusTreeNode node = new BPlusTreeNode(page);
                if (node.isLeaf()) {
                    return current;
                }
                current = node.child(node.childIndexFor(key));
            }
        }
    }

    private boolean containsInLeaf(int leafPage, int key) {
        try (Page page = pool.getPage(leafPage)) {
            return new BPlusTreeNode(page).findInLeaf(key) >= 0;
        }
    }

    /**
     * Recursive insert. Returns the split of this node that the parent must absorb, or
     * {@code null}. Ancestors are only modified after the leaf insert succeeded, so a
     * duplicate key leaves every page untouched.
     */
    private Split insertInto(int pageNum, int key, long value) {
        try (Page page = pool.getPage(pageNum)) {
            BPlusTreeNode node = new BPlusTreeNode(page);
            if (node.isLeaf()) {
                int index = node.findInLeaf(key);
                if (index >= 0) {
                    throw new DbException("duplicate key: " + key);
                }
                return insertIntoLeaf(pageNum, -index - 1, key, value);
            }
            int childIndex = node.childIndexFor(key);
            Split split = insertInto(node.child(childIndex), key, value);
            return split == null ? null : insertSeparator(pageNum, childIndex, split);
        }
    }

    private Split insertIntoLeaf(int pageNum, int position, int key, long value) {
        try (Page page = pool.getPageForWrite(pageNum)) {
            BPlusTreeNode leaf = new BPlusTreeNode(page);
            int count = leaf.keyCount();
            if (count < maxLeafKeys) {
                leaf.insertLeafEntry(position, key, value);
                return null;
            }
            int total = count + 1;
            int[] keys = new int[total];
            long[] values = new long[total];
            for (int i = 0, src = 0; i < total; i++) {
                if (i == position) {
                    keys[i] = key;
                    values[i] = value;
                } else {
                    keys[i] = leaf.key(src);
                    values[i] = leaf.value(src);
                    src++;
                }
            }
            int leftCount = (total + 1) / 2;
            try (Page newPage = pool.createNewPage()) {
                BPlusTreeNode right = new BPlusTreeNode(newPage);
                right.initLeaf();
                right.setLeafEntries(keys, values, leftCount, total);
                right.setNext(leaf.next());
                leaf.setLeafEntries(keys, values, 0, leftCount);
                leaf.setNext(newPage.getPageNum());
                return new Split(keys[leftCount], newPage.getPageNum());
            }
        }
    }

    private Split insertSeparator(int pageNum, int position, Split child) {
        try (Page page = pool.getPageForWrite(pageNum)) {
            BPlusTreeNode node = new BPlusTreeNode(page);
            int count = node.keyCount();
            if (count < maxInternalKeys) {
                node.insertSeparator(position, child.key(), child.rightPage());
                return null;
            }
            int total = count + 1;
            int[] keys = new int[total];
            int[] children = new int[total + 1];
            for (int i = 0, src = 0; i < total; i++) {
                if (i == position) {
                    keys[i] = child.key();
                } else {
                    keys[i] = node.key(src++);
                }
            }
            for (int i = 0, src = 0; i <= total; i++) {
                if (i == position + 1) {
                    children[i] = child.rightPage();
                } else {
                    children[i] = node.child(src++);
                }
            }
            int median = total / 2;
            try (Page newPage = pool.createNewPage()) {
                BPlusTreeNode right = new BPlusTreeNode(newPage);
                right.initInternal();
                right.setInternalEntries(keys, children, median + 1, total);
                node.setInternalEntries(keys, children, 0, median);
                return new Split(keys[median], newPage.getPageNum());
            }
        }
    }

    /**
     * Handles a split of the root: the root page already holds the left half; move it to a
     * fresh page and turn the root into an internal node over (left copy, right).
     */
    private void growRoot(Split split) {
        try (Page left = pool.createNewPage(); Page root = pool.getPageForWrite(rootPageNum)) {
            left.copyFrom(root.getData());
            BPlusTreeNode node = new BPlusTreeNode(root);
            node.initInternal();
            node.setInternalEntries(new int[] {split.key()},
                    new int[] {left.getPageNum(), split.rightPage()}, 0, 1);
        }
    }
}
