package com.minidb.transaction;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertFalse;
import static com.minidb.testkit.MiniTest.assertThrows;
import static com.minidb.testkit.MiniTest.assertTrue;
import static com.minidb.testkit.MiniTest.test;

import com.minidb.DbException;
import com.minidb.storage.Page;
import com.minidb.storage.Pager;
import com.minidb.testkit.MiniTest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/** Tests for {@link Journal}. */
public final class JournalTest {

    private static final int HEADER = 20;
    private static final int RECORD = 4 + Page.SIZE + 4;

    private JournalTest() {
    }

    /** Test fixture: a temp dir with a pager of {@code pages} pages, page i filled with byte i+1. */
    private static final class Env implements AutoCloseable {
        final Path dir = MiniTest.tempDir("journaltest");
        final Path dbFile = dir.resolve("t.db");
        final Path jFile = dir.resolve("t.journal");
        Pager pager = new Pager(dbFile);
        final Journal journal = new Journal(jFile);

        Env(int pages) {
            for (int i = 0; i < pages; i++) {
                Page p = pager.createNewPage();
                p.fill(0, Page.SIZE, (byte) (i + 1));
                pager.flushPage(p);
            }
            pager.sync();
        }

        /** Journals the current content of a page and then overwrites it with the given byte. */
        void modify(int n, byte value) {
            journal.recordOriginal(n, pager.getPage(n).copyData());
            Page p = pager.getPage(n);
            p.fill(0, Page.SIZE, value);
            pager.flushPage(p);
        }

        int firstByte(int n) {
            return pager.getPage(n).getByte(0);
        }

        /** Simulates a process crash: handles closed, nothing committed. */
        void crashAndReopen() {
            journal.close();
            pager.close();
            pager = new Pager(dbFile);
        }

        @Override
        public void close() {
            journal.close();
            pager.close();
            MiniTest.deleteRecursively(dir);
        }
    }

    private static byte[] image(int value) {
        byte[] b = new byte[Page.SIZE];
        java.util.Arrays.fill(b, (byte) value);
        return b;
    }

    private static int crc(byte[]... parts) {
        CRC32 c = new CRC32();
        for (byte[] p : parts) {
            c.update(p);
        }
        return (int) c.getValue();
    }

    private static void truncateFile(Path file, long size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size);
        }
    }

    /**
     * Runs all journal tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        test("begin twice throws", () -> {
            try (Env e = new Env(1)) {
                e.journal.begin(1);
                assertThrows(DbException.class, () -> e.journal.begin(1), "second begin");
                assertTrue(e.journal.isActive(), "still active");
            }
        });

        test("recordOriginal when inactive is a no-op", () -> {
            try (Env e = new Env(2)) {
                e.journal.recordOriginal(0, image(9));
                assertFalse(Files.exists(e.jFile), "no file");
                assertFalse(e.journal.isJournaled(0), "not journaled");
                assertEquals(-1, e.journal.getOriginalPageCount(), "inactive count");
            }
        });

        test("journal file is created lazily", () -> {
            try (Env e = new Env(2)) {
                e.journal.begin(2);
                assertFalse(Files.exists(e.jFile), "no file after begin");
                e.journal.sync();
                assertTrue(Files.exists(e.jFile), "sync of an active journal creates the header");
                e.journal.rollback(e.pager);
                e.journal.begin(2);
                e.journal.recordOriginal(1, image(5));
                assertTrue(Files.exists(e.jFile), "file after first record");
                assertEquals(2, e.journal.getOriginalPageCount(), "original count");
                assertEquals(e.jFile, e.journal.getPath(), "path");
            }
        });

        test("header and record bytes are exact", () -> {
            try (Env e = new Env(3)) {
                e.journal.begin(3);
                e.journal.recordOriginal(2, image(7));
                e.journal.sync();
                byte[] f = Files.readAllBytes(e.jFile);
                assertEquals(HEADER + RECORD, f.length, "file length");
                ByteBuffer b = ByteBuffer.wrap(f);
                byte[] magic = new byte[8];
                b.get(magic);
                assertEquals("MDBJRNL1", new String(magic, StandardCharsets.US_ASCII), "magic");
                assertEquals(3, b.getInt(), "original page count");
                assertEquals(4096, b.getInt(), "page size");
                assertEquals(crc(java.util.Arrays.copyOfRange(f, 0, 16)), b.getInt(), "header crc");
                assertEquals(2, b.getInt(), "page number");
                byte[] img = new byte[Page.SIZE];
                b.get(img);
                assertTrue(java.util.Arrays.equals(image(7), img), "before-image");
                byte[] num = ByteBuffer.allocate(4).putInt(2).array();
                assertEquals(crc(num, img), b.getInt(), "record crc");
            }
        });

        test("duplicates journaled once, pages >= original ignored", () -> {
            try (Env e = new Env(2)) {
                e.journal.begin(2);
                e.journal.recordOriginal(0, image(1));
                e.journal.recordOriginal(0, image(99));
                e.journal.recordOriginal(2, image(3));
                e.journal.recordOriginal(50, image(3));
                e.journal.sync();
                assertEquals((long) HEADER + RECORD, Files.size(e.jFile), "one record only");
                assertTrue(e.journal.isJournaled(0), "page 0 journaled");
                assertFalse(e.journal.isJournaled(2), "page 2 not journaled");
                assertThrows(IllegalArgumentException.class,
                        () -> e.journal.recordOriginal(1, new byte[10]), "bad length");
            }
        });

        test("rollback restores pages and truncates appended pages", () -> {
            try (Env e = new Env(3)) {
                e.journal.begin(e.pager.getPageCount());
                e.modify(0, (byte) 70);
                e.modify(2, (byte) 72);
                e.modify(2, (byte) 73);
                e.pager.createNewPage();
                e.pager.createNewPage();
                e.journal.recordOriginal(4, image(0));
                assertEquals(5, e.pager.getPageCount(), "grown");
                e.journal.rollback(e.pager);
                assertEquals(3, e.pager.getPageCount(), "truncated");
                assertEquals(1, e.firstByte(0), "page 0 restored");
                assertEquals(2, e.firstByte(1), "page 1 untouched");
                assertEquals(3, e.firstByte(2), "page 2 restored");
                assertFalse(Files.exists(e.jFile), "journal deleted");
                assertFalse(e.journal.isActive(), "inactive");
            }
        });

        test("commit deletes the file and keeps new data", () -> {
            try (Env e = new Env(2)) {
                e.journal.begin(2);
                e.modify(1, (byte) 55);
                Page np = e.pager.createNewPage();
                np.fill(0, Page.SIZE, (byte) 66);
                e.pager.flushPage(np);
                e.journal.sync();
                e.pager.sync();
                e.journal.commit();
                assertFalse(Files.exists(e.jFile), "journal deleted");
                assertFalse(e.journal.isActive(), "inactive");
                assertEquals(3, e.pager.getPageCount(), "count kept");
                assertEquals(55, e.firstByte(1), "modification kept");
                assertEquals(66, e.firstByte(2), "new page kept");
            }
        });

        test("commit and rollback when inactive throw", () -> {
            try (Env e = new Env(1)) {
                assertThrows(DbException.class, e.journal::commit, "commit");
                assertThrows(DbException.class, () -> e.journal.rollback(e.pager), "rollback");
            }
        });

        test("rollback with nothing journaled is safe", () -> {
            try (Env e = new Env(2)) {
                e.journal.begin(2);
                e.journal.rollback(e.pager);
                assertEquals(2, e.pager.getPageCount(), "count");
                assertEquals(1, e.firstByte(0), "data");
            }
        });

        test("crash recovery restores data, truncates, deletes journal", () -> {
            try (Env e = new Env(3)) {
                e.journal.begin(3);
                e.modify(0, (byte) 90);
                e.modify(1, (byte) 91);
                e.pager.createNewPage();
                e.pager.createNewPage();
                e.journal.sync();
                e.pager.sync();
                e.crashAndReopen();
                assertEquals(5, e.pager.getPageCount(), "crashed state");
                assertTrue(Journal.recover(e.jFile, e.pager), "recover returns true");
                assertEquals(3, e.pager.getPageCount(), "truncated");
                assertEquals(1, e.firstByte(0), "page 0");
                assertEquals(2, e.firstByte(1), "page 1");
                assertEquals(3, e.firstByte(2), "page 2");
                assertFalse(Files.exists(e.jFile), "journal deleted");
            }
        });

        test("recovery with only truncation returns true", () -> {
            try (Env e = new Env(2)) {
                e.journal.begin(2);
                e.modify(0, (byte) 9);
                e.journal.sync();
                e.pager.createNewPage();
                e.crashAndReopen();
                truncateFile(e.jFile, HEADER);
                assertTrue(Journal.recover(e.jFile, e.pager), "truncation counts");
                assertEquals(2, e.pager.getPageCount(), "truncated");
            }
        });

        test("torn tail record is ignored", () -> {
            try (Env e = new Env(3)) {
                e.journal.begin(3);
                e.modify(0, (byte) 90);
                e.modify(1, (byte) 91);
                e.modify(2, (byte) 92);
                e.journal.sync();
                e.crashAndReopen();
                truncateFile(e.jFile, HEADER + 2L * RECORD + 1000);
                assertTrue(Journal.recover(e.jFile, e.pager), "recovered");
                assertEquals(1, e.firstByte(0), "page 0 restored");
                assertEquals(2, e.firstByte(1), "page 1 restored");
                assertEquals(92, e.firstByte(2), "page 2 torn, left alone");
                assertFalse(Files.exists(e.jFile), "journal deleted");
            }
        });

        test("corrupted record CRC stops replay", () -> {
            try (Env e = new Env(3)) {
                e.journal.begin(3);
                e.modify(0, (byte) 90);
                e.modify(1, (byte) 91);
                e.modify(2, (byte) 92);
                e.journal.sync();
                e.crashAndReopen();
                try (FileChannel ch = FileChannel.open(e.jFile, StandardOpenOption.WRITE)) {
                    ch.write(ByteBuffer.wrap(new byte[] {0x55}), HEADER + RECORD + 100L);
                }
                assertTrue(Journal.recover(e.jFile, e.pager), "recovered");
                assertEquals(1, e.firstByte(0), "page 0 restored");
                assertEquals(91, e.firstByte(1), "corrupt record not applied");
                assertEquals(92, e.firstByte(2), "later record not applied");
            }
        });

        test("bad header deletes the file and leaves the database untouched", () -> {
            try (Env e = new Env(2)) {
                e.modify(0, (byte) 80);
                for (int variant = 0; variant < 4; variant++) {
                    byte[] junk = new byte[HEADER + RECORD];
                    switch (variant) {
                        case 0 -> { /* all zeros: bad magic */ }
                        case 1 -> junk = new byte[10];
                        case 2 -> {
                            ByteBuffer b = ByteBuffer.wrap(junk);
                            b.put(Journal.MAGIC.getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(4096).putInt(12345);
                        }
                        default -> {
                            ByteBuffer b = ByteBuffer.wrap(junk);
                            b.put(Journal.MAGIC.getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(512);
                            b.putInt(crc(java.util.Arrays.copyOfRange(junk, 0, 16)));
                        }
                    }
                    Files.write(e.jFile, junk);
                    assertFalse(Journal.recover(e.jFile, e.pager), "variant " + variant);
                    assertFalse(Files.exists(e.jFile), "deleted, variant " + variant);
                    assertEquals(80, e.firstByte(0), "db untouched, variant " + variant);
                    assertEquals(2, e.pager.getPageCount(), "count untouched, variant " + variant);
                }
            }
        });

        test("recover without journal file returns false", () -> {
            try (Env e = new Env(1)) {
                assertFalse(Journal.recover(e.jFile, e.pager), "no file");
            }
        });

        test("close is idempotent and keeps the active journal on disk", () -> {
            try (Env e = new Env(1)) {
                e.journal.begin(1);
                e.journal.recordOriginal(0, image(1));
                e.journal.close();
                e.journal.close();
                assertTrue(Files.exists(e.jFile), "file kept");
            }
        });

        test("journal is reusable after commit and rollback", () -> {
            try (Env e = new Env(2)) {
                e.journal.begin(2);
                e.modify(0, (byte) 31);
                e.journal.sync();
                e.journal.commit();
                assertEquals(31, e.firstByte(0), "first commit");

                e.journal.begin(2);
                assertFalse(e.journal.isJournaled(0), "state cleared");
                e.modify(0, (byte) 32);
                e.journal.rollback(e.pager);
                assertEquals(31, e.firstByte(0), "rollback to first commit");

                e.journal.begin(2);
                e.modify(1, (byte) 33);
                e.journal.sync();
                e.journal.commit();
                assertEquals(33, e.firstByte(1), "third transaction");
                assertFalse(Files.exists(e.jFile), "no leftovers");
            }
        });

        test("rollback of a transaction with 300 journaled pages restores all", () -> {
            try (Env e = new Env(300)) {
                e.journal.begin(300);
                for (int i = 0; i < 300; i++) {
                    e.modify(i, (byte) (i % 100 + 101));
                }
                e.pager.createNewPage();
                e.journal.rollback(e.pager);
                assertEquals(300, e.pager.getPageCount(), "truncated");
                for (int i = 0; i < 300; i++) {
                    assertEquals((i + 1) & 0xFF, e.firstByte(i) & 0xFF, "page " + i);
                }
                assertFalse(Files.exists(e.jFile), "journal deleted");
            }
        });

        test("sync on an active journal without records creates a header-only file", () -> {
            try (Env e = new Env(2)) {
                e.journal.begin(2);
                e.journal.sync();
                assertTrue(Files.exists(e.jFile), "file created");
                assertEquals((long) HEADER, Files.size(e.jFile), "header only");
                e.pager.createNewPage();
                e.pager.createNewPage();
                e.crashAndReopen();
                assertEquals(4, e.pager.getPageCount(), "grown before crash");
                assertTrue(Journal.recover(e.jFile, e.pager), "recover truncates");
                assertEquals(2, e.pager.getPageCount(), "truncated");
                assertFalse(Files.exists(e.jFile), "journal deleted");
            }
        });

        test("recording after sync and close keeps earlier records", () -> {
            try (Env e = new Env(3)) {
                e.journal.begin(3);
                e.modify(0, (byte) 90);
                e.journal.close();
                e.modify(1, (byte) 91);
                e.journal.sync();
                assertEquals((long) HEADER + 2L * RECORD, Files.size(e.jFile), "two records");
                e.journal.rollback(e.pager);
                assertEquals(1, e.firstByte(0), "page 0");
                assertEquals(2, e.firstByte(1), "page 1");
            }
        });

        test("streaming recovery of many records with a torn tail", () -> {
            try (Env e = new Env(50)) {
                e.journal.begin(50);
                for (int i = 0; i < 50; i++) {
                    e.modify(i, (byte) 120);
                }
                e.journal.sync();
                e.crashAndReopen();
                truncateFile(e.jFile, HEADER + 40L * RECORD + 17);
                assertTrue(Journal.recover(e.jFile, e.pager), "recovered");
                for (int i = 0; i < 50; i++) {
                    assertEquals(i < 40 ? i + 1 : 120, e.firstByte(i), "page " + i);
                }
                assertFalse(Files.exists(e.jFile), "journal deleted");
            }
        });

        MiniTest.finish();
    }
}
