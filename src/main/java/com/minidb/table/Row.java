package com.minidb.table;

import com.minidb.DbException;
import com.minidb.storage.Page;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One table row with the fixed on-disk layout of 260 bytes:
 * <pre>
 * offset 0  int       id           (4 bytes, big-endian)
 * offset 4  byte      name length  (unsigned, number of UTF-8 bytes, 0..255)
 * offset 5  byte[255] name         (UTF-8, zero padded)
 * </pre>
 *
 * @param id   the primary-key value
 * @param name the text value (at most 255 UTF-8 bytes)
 */
public record Row(int id, String name) {

    /** Bytes used by the id field. */
    public static final int ID_SIZE = 4;
    /** Bytes used by the name-length field. */
    public static final int NAME_LENGTH_SIZE = 1;
    /** Maximum number of UTF-8 bytes in a name. */
    public static final int MAX_NAME_BYTES = 255;
    /** Total serialized size of a row: 4 + 1 + 255 = 260 bytes. */
    public static final int SIZE = ID_SIZE + NAME_LENGTH_SIZE + MAX_NAME_BYTES;

    /**
     * Validates the row.
     *
     * @param id   the primary-key value
     * @param name the text value
     * @throws DbException if the name is null or longer than 255 UTF-8 bytes
     */
    public Row {
        if (name == null) {
            throw new DbException("name must not be null");
        }
        int len = encodedLength(name);
        if (len > MAX_NAME_BYTES) {
            throw new DbException("value too long: " + len + " bytes (max " + MAX_NAME_BYTES + ")");
        }
    }

    /**
     * Returns the UTF-8 length of the text, rejecting strings that cannot be encoded
     * losslessly (e.g. lone surrogates, which {@link String#getBytes} would silently turn
     * into {@code '?'}).
     */
    private static int encodedLength(String text) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return encoder.encode(CharBuffer.wrap(text)).remaining();
        } catch (CharacterCodingException e) {
            throw new DbException("value is not valid Unicode text");
        }
    }

    /**
     * Serializes this row into a page.
     *
     * @param page   destination page (must already be obtained for writing)
     * @param offset byte offset of the row inside the page
     */
    public void writeTo(Page page, int offset) {
        Objects.requireNonNull(page, "page");
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        page.putInt(offset, id);
        page.putByte(offset + ID_SIZE, (byte) bytes.length);
        page.putBytes(offset + ID_SIZE + NAME_LENGTH_SIZE, bytes);
        page.fill(offset + ID_SIZE + NAME_LENGTH_SIZE + bytes.length, MAX_NAME_BYTES - bytes.length, (byte) 0);
    }

    /**
     * Deserializes a row from a page.
     *
     * @param page   source page
     * @param offset byte offset of the row inside the page
     * @return the decoded row
     */
    public static Row readFrom(Page page, int offset) {
        int id = page.getInt(offset);
        int len = page.getByte(offset + ID_SIZE) & 0xFF;
        byte[] bytes = page.getBytes(offset + ID_SIZE + NAME_LENGTH_SIZE, len);
        return new Row(id, new String(bytes, StandardCharsets.UTF_8));
    }
}
