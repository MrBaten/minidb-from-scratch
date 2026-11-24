package com.minidb.query;

import static com.minidb.testkit.MiniTest.assertEquals;
import static com.minidb.testkit.MiniTest.assertThrows;
import static com.minidb.testkit.MiniTest.assertTrue;
import static com.minidb.testkit.MiniTest.test;

import com.minidb.DbException;
import com.minidb.query.Statement.Comparison;
import com.minidb.query.Statement.Op;
import com.minidb.query.Statement.Tuple;
import com.minidb.testkit.MiniTest;

import java.util.List;

/** Tests for {@link SqlParser}. */
public final class SqlParserTest {

    private SqlParserTest() {
    }

    private static Statement parse(String sql) {
        return SqlParser.parse(sql);
    }

    private static void assertSyntaxError(String sql) {
        DbException e = assertThrows(DbException.class, () -> parse(sql), "expected syntax error for: " + sql);
        assertTrue(e.getMessage().startsWith("syntax error: "), "bad message for [" + sql + "]: " + e.getMessage());
    }

    private static Comparison cmp(String col, Op op, Object v) {
        return new Comparison(col, op, v);
    }

    /**
     * Runs all parser tests.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        test("create table INT/VARCHAR", () -> assertEquals(
                new Statement.CreateTable("users", "id", "name"),
                parse("CREATE TABLE users (id INT, name VARCHAR(20))"), "create"));
        test("create table INTEGER/TEXT", () -> assertEquals(
                new Statement.CreateTable("t", "k", "v"), parse("create table t (k integer, v text)"), "create"));
        test("create VARCHAR(255) and VARCHAR(1) and bare VARCHAR", () -> {
            assertEquals(new Statement.CreateTable("t", "a", "b"), parse("CREATE TABLE t (a INT, b VARCHAR(255))"), "255");
            assertEquals(new Statement.CreateTable("t", "a", "b"), parse("CREATE TABLE t (a INT, b VARCHAR(1))"), "1");
            assertEquals(new Statement.CreateTable("t", "a", "b"), parse("CREATE TABLE t (a INT, b VARCHAR)"), "bare");
            assertEquals(new Statement.CreateTable("t", "a", "b"), parse("CREATE TABLE t (a INT, b TEXT(10))"), "text n");
        });
        test("insert single row", () -> assertEquals(
                new Statement.Insert("users", List.of(new Tuple(1, "Jewel"))),
                parse("INSERT INTO users VALUES (1, 'Jewel')"), "insert"));
        test("insert multi-row", () -> assertEquals(
                new Statement.Insert("users", List.of(new Tuple(1, "a"), new Tuple(2, "b"), new Tuple(3, "c"))),
                parse("INSERT INTO users VALUES (1,'a'),(2, 'b') ,\n (3,'c');"), "insert"));
        test("negative integers", () -> {
            assertEquals(new Statement.Insert("t", List.of(new Tuple(-42, "x"))), parse("INSERT INTO t VALUES (-42, 'x')"), "ins");
            assertEquals(new Statement.Select("t", List.of(), List.of(cmp("id", Op.GT, -5L))),
                    parse("SELECT * FROM t WHERE id > -5"), "where");
            assertEquals(new Statement.Insert("t", List.of(new Tuple(Long.MIN_VALUE, "x"))),
                    parse("INSERT INTO t VALUES (-9223372036854775808, 'x')"), "min");
        });
        test("string escapes, empty and unicode", () -> {
            assertEquals(new Statement.Insert("t", List.of(new Tuple(1, "it's"))), parse("INSERT INTO t VALUES (1, 'it''s')"), "esc");
            assertEquals(new Statement.Insert("t", List.of(new Tuple(1, "'"))), parse("INSERT INTO t VALUES (1, '''')"), "only quote");
            assertEquals(new Statement.Insert("t", List.of(new Tuple(1, ""))), parse("INSERT INTO t VALUES (1, '')"), "empty");
            assertEquals(new Statement.Insert("t", List.of(new Tuple(1, "জুয়েল é 日本 😀 ; ( ) --"))),
                    parse("INSERT INTO t VALUES (1, 'জুয়েল é 日本 😀 ; ( ) --')"), "unicode");
        });
        test("select star / columns", () -> {
            assertEquals(new Statement.Select("users", List.of(), List.of()), parse("SELECT * FROM users"), "star");
            assertEquals(new Statement.Select("users", List.of("id", "Name"), List.of()),
                    parse("SELECT id, Name FROM users"), "cols");
        });
        test("select where equality / strings / all operators", () -> {
            assertEquals(new Statement.Select("users", List.of(), List.of(cmp("id", Op.EQ, 1L))),
                    parse("select * from users where id = 1"), "eq");
            assertEquals(new Statement.Select("users", List.of(), List.of(cmp("name", Op.EQ, "x"))),
                    parse("SELECT * FROM users WHERE name = 'x'"), "str");
            for (Object[] c : new Object[][] {{"=", Op.EQ}, {"<", Op.LT}, {"<=", Op.LE}, {">", Op.GT}, {">=", Op.GE}}) {
                assertEquals(new Statement.Delete("t", List.of(cmp("id", (Op) c[1], 7L))),
                        parse("DELETE FROM t WHERE id " + c[0] + " 7"), "op " + c[0]);
            }
        });
        test("where AND chain and BETWEEN", () -> {
            assertEquals(new Statement.Select("t", List.of("a"),
                            List.of(cmp("id", Op.GE, 1L), cmp("id", Op.LT, 10L), cmp("name", Op.EQ, "z"))),
                    parse("SELECT a FROM t WHERE id >= 1 AND id < 10 AND name = 'z'"), "chain");
            assertEquals(new Statement.Select("t", List.of(), List.of(cmp("id", Op.GE, 5L), cmp("id", Op.LE, 9L))),
                    parse("SELECT * FROM t WHERE id BETWEEN 5 AND 9"), "between");
            assertEquals(new Statement.Select("t", List.of(),
                            List.of(cmp("id", Op.GE, 5L), cmp("id", Op.LE, 9L), cmp("name", Op.EQ, "q"))),
                    parse("SELECT * FROM t WHERE id between 5 and 9 and name = 'q'"), "between+and");
        });
        test("delete and update", () -> {
            assertEquals(new Statement.Delete("t", List.of()), parse("DELETE FROM t"), "delete all");
            assertEquals(new Statement.Delete("t", List.of(cmp("id", Op.EQ, 3L))), parse("DELETE FROM t WHERE id = 3;"), "delete");
            assertEquals(new Statement.Update("t", "name", "new", List.of()), parse("UPDATE t SET name = 'new'"), "update all");
            assertEquals(new Statement.Update("t", "name", "n", List.of(cmp("id", Op.EQ, 3L))),
                    parse("UPDATE t SET name = 'n' WHERE id = 3"), "update");
        });
        test("transaction statements", () -> {
            for (String s : new String[] {"BEGIN", "begin transaction", "START TRANSACTION", "Start  Transaction;"}) {
                assertEquals(new Statement.Begin(), parse(s), s);
            }
            assertEquals(new Statement.Commit(), parse("COMMIT"), "commit");
            assertEquals(new Statement.Commit(), parse("commit transaction;"), "commit tx");
            assertEquals(new Statement.Rollback(), parse("ROLLBACK"), "rollback");
            assertEquals(new Statement.Rollback(), parse("RollBack Transaction"), "rollback tx");
        });
        test("show tables", () -> {
            assertEquals(new Statement.ShowTables(), parse("SHOW TABLES"), "show");
            assertEquals(new Statement.ShowTables(), parse("  show   tables ; "), "show spaced");
        });
        test("mixed case keywords, identifier case preserved", () -> assertEquals(
                new Statement.Select("Users", List.of("Id"), List.of(cmp("Id", Op.EQ, 1L))),
                parse("SeLeCt Id FrOm Users WhErE Id = 1"), "case"));
        test("whitespace: tabs and newlines", () -> assertEquals(
                new Statement.Select("t", List.of(), List.of(cmp("id", Op.EQ, 1L))),
                parse("\t SELECT\n*\r\nFROM\tt\n WHERE\n id\t=\n1 \n;\n"), "ws"));
        test("no spaces around symbols", () -> assertEquals(
                new Statement.Insert("t", List.of(new Tuple(1, "a"))), parse("INSERT INTO t VALUES(1,'a')"), "tight"));
        test("identifiers with underscores and digits", () -> assertEquals(
                new Statement.Select("_t1", List.of("col_2"), List.of()), parse("SELECT col_2 FROM _t1"), "ident"));
        test("table named like keyword prefix is fine", () -> assertEquals(
                new Statement.Select("selection", List.of(), List.of()), parse("SELECT * FROM selection"), "prefix"));

        test("error: missing FROM has helpful message", () -> {
            DbException e = assertThrows(DbException.class, () -> parse("SELECT * users"), "missing from");
            assertEquals("syntax error: expected FROM but found 'users' at position 9", e.getMessage(), "message");
        });
        test("error: empty / null / blank / only semicolon", () -> {
            for (String s : new String[] {"", "   ", "\n\t", ";", " ; "}) {
                DbException e = assertThrows(DbException.class, () -> parse(s), "empty [" + s + "]");
                assertEquals("syntax error: empty statement", e.getMessage(), "message");
            }
            DbException e = assertThrows(DbException.class, () -> parse(null), "null");
            assertEquals("syntax error: empty statement", e.getMessage(), "null message");
        });
        test("error: missing / unbalanced parentheses", () -> {
            assertSyntaxError("INSERT INTO t VALUES 1, 'a')");
            assertSyntaxError("INSERT INTO t VALUES (1, 'a'");
            assertSyntaxError("CREATE TABLE t id INT, name TEXT)");
            assertSyntaxError("CREATE TABLE t (id INT, name VARCHAR(20)");
            assertSyntaxError("CREATE TABLE t (id INT, name VARCHAR(20)))");
        });
        test("error: unterminated string", () -> {
            DbException e = assertThrows(DbException.class, () -> parse("INSERT INTO t VALUES (1, 'abc)"), "unterminated");
            assertTrue(e.getMessage().startsWith("syntax error: unterminated string literal"), e.getMessage());
            assertSyntaxError("SELECT * FROM t WHERE name = 'a''");
        });
        test("error: trailing garbage and multiple statements", () -> {
            assertSyntaxError("SELECT * FROM t garbage");
            assertSyntaxError("SELECT * FROM t;;");
            assertSyntaxError("SELECT * FROM t; SELECT * FROM t");
            assertSyntaxError("BEGIN; COMMIT;");
            assertSyntaxError("SHOW TABLES now");
            assertSyntaxError("COMMIT TRANSACTION TRANSACTION");
        });
        test("error: keyword used as identifier", () -> {
            assertSyntaxError("SELECT * FROM select");
            assertSyntaxError("CREATE TABLE table (id INT, v TEXT)");
            assertSyntaxError("CREATE TABLE t (from INT, v TEXT)");
            assertSyntaxError("SELECT where FROM t");
            assertSyntaxError("UPDATE t SET set = 'x'");
        });
        test("error: integer overflow", () -> {
            DbException e = assertThrows(DbException.class,
                    () -> parse("INSERT INTO t VALUES (99999999999999999999, 'x')"), "overflow");
            assertTrue(e.getMessage().startsWith("syntax error: integer literal out of range"), e.getMessage());
            assertSyntaxError("SELECT * FROM t WHERE id = 9223372036854775808");
            assertSyntaxError("SELECT * FROM t WHERE id = -9223372036854775809");
        });
        test("error: != and <> operators", () -> {
            DbException e = assertThrows(DbException.class, () -> parse("SELECT * FROM t WHERE id != 1"), "!=");
            assertEquals("syntax error: operator != is not supported", e.getMessage(), "message");
            DbException e2 = assertThrows(DbException.class, () -> parse("SELECT * FROM t WHERE id <> 1"), "<>");
            assertEquals("syntax error: operator <> is not supported", e2.getMessage(), "message");
        });
        test("error: INSERT tuple arity and types", () -> {
            assertSyntaxError("INSERT INTO t VALUES (1)");
            assertSyntaxError("INSERT INTO t VALUES (1, 'a', 'b')");
            assertSyntaxError("INSERT INTO t VALUES ('a', 1)");
            assertSyntaxError("INSERT INTO t VALUES (1, 2)");
            assertSyntaxError("INSERT INTO t VALUES (1, 'a'),");
            assertSyntaxError("INSERT INTO t VALUES");
            assertSyntaxError("INSERT t VALUES (1, 'a')");
        });
        test("error: VARCHAR length out of range", () -> {
            assertSyntaxError("CREATE TABLE t (id INT, v VARCHAR(0))");
            assertSyntaxError("CREATE TABLE t (id INT, v VARCHAR(256))");
            assertSyntaxError("CREATE TABLE t (id INT, v VARCHAR(-1))");
            assertSyntaxError("CREATE TABLE t (id INT, v VARCHAR())");
            assertSyntaxError("CREATE TABLE t (id INT, v VARCHAR('x'))");
        });
        test("error: bad CREATE TABLE shapes", () -> {
            assertSyntaxError("CREATE TABLE t (id TEXT, v INT)");
            assertSyntaxError("CREATE TABLE t (id INT)");
            assertSyntaxError("CREATE TABLE t (id INT, v TEXT, extra INT)");
            assertSyntaxError("CREATE TABLE (id INT, v TEXT)");
            assertSyntaxError("CREATE t (id INT, v TEXT)");
        });
        test("error: bad WHERE clauses", () -> {
            assertSyntaxError("SELECT * FROM t WHERE");
            assertSyntaxError("SELECT * FROM t WHERE id");
            assertSyntaxError("SELECT * FROM t WHERE id =");
            assertSyntaxError("SELECT * FROM t WHERE id = 1 AND");
            assertSyntaxError("SELECT * FROM t WHERE id = 1 OR id = 2");
            assertSyntaxError("SELECT * FROM t WHERE id BETWEEN 1");
            assertSyntaxError("SELECT * FROM t WHERE id BETWEEN 1 AND 'x'");
            assertSyntaxError("SELECT * FROM t WHERE id BETWEEN 'a' AND 'b'");
            assertSyntaxError("SELECT * FROM t WHERE 1 = id");
            assertSyntaxError("SELECT * FROM t WHERE id == 1");
            assertSyntaxError("SELECT * FROM t WHERE id = name");
        });
        test("error: bad SELECT / UPDATE / DELETE shapes", () -> {
            assertSyntaxError("SELECT FROM t");
            assertSyntaxError("SELECT a, FROM t");
            assertSyntaxError("SELECT *, a FROM t");
            assertSyntaxError("SELECT * FROM");
            assertSyntaxError("DELETE t");
            assertSyntaxError("UPDATE t name = 'x'");
            assertSyntaxError("UPDATE t SET name = 5");
            assertSyntaxError("UPDATE t SET name = 'x', other = 'y'");
        });
        test("error: unknown statements and stray characters", () -> {
            assertSyntaxError("DROP TABLE t");
            assertSyntaxError("hello");
            assertSyntaxError("42");
            assertSyntaxError("'abc'");
            assertSyntaxError("SELECT * FROM t WHERE id = 1 #");
            assertSyntaxError("SELECT * FROM t WHERE id = 12abc");
            assertSyntaxError("SELECT * FROM t WHERE id = -");
            assertSyntaxError("SHOW");
            assertSyntaxError("START");
        });
        test("error positions refer to the offending token", () -> {
            DbException e = assertThrows(DbException.class, () -> parse("SELECT * FROM t WHERE id ? 1"), "?");
            assertEquals("syntax error: unexpected character '?' at position 25", e.getMessage(), "message");
            DbException e2 = assertThrows(DbException.class, () -> parse("SELECT *"), "eof");
            assertEquals("syntax error: expected FROM but found end of input at position 8", e2.getMessage(), "eof message");
        });
        test("returned lists are immutable copies", () -> {
            Statement.Select s = (Statement.Select) parse("SELECT a FROM t WHERE id = 1");
            assertThrows(UnsupportedOperationException.class, () -> s.columns().add("x"), "immutable");
        });

        MiniTest.finish();
    }
}
