package com.minidb.query;

import com.minidb.DbException;
import com.minidb.query.Statement.Comparison;
import com.minidb.query.Statement.Op;
import com.minidb.query.Statement.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hand-written tokenizer and recursive-descent parser for the minidb SQL dialect
 * (grammar in PLAN.md §3.9).
 *
 * <p>Every failure is reported as a {@link DbException} whose message starts with
 * {@code "syntax error: "}.</p>
 */
public final class SqlParser {

    /** Words that can never be used as table or column names. */
    private static final Set<String> KEYWORDS = Set.of(
            "CREATE", "TABLE", "TABLES", "INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE",
            "DELETE", "UPDATE", "SET", "AND", "BETWEEN", "BEGIN", "START", "TRANSACTION",
            "COMMIT", "ROLLBACK", "SHOW", "INT", "INTEGER", "VARCHAR", "TEXT");

    private static final int MAX_VARCHAR = 255;

    private SqlParser() {
    }

    /**
     * Parses one SQL statement.
     *
     * @param sql statement text (an optional trailing semicolon is allowed)
     * @return the statement AST
     * @throws DbException {@code "syntax error: ..."} on invalid input
     */
    public static Statement parse(String sql) {
        if (sql == null) {
            throw syntax("empty statement");
        }
        return new Parser(new Lexer(sql).tokenize()).parseStatement();
    }

    private static DbException syntax(String detail) {
        return new DbException("syntax error: " + detail);
    }

    // ------------------------------------------------------------------ lexer

    /** Kinds of lexical tokens. */
    private enum Type { KEYWORD, IDENT, INTEGER, STRING, SYMBOL, EOF }

    /**
     * One lexical token.
     *
     * @param type kind of token
     * @param text raw text for words/symbols/integers; the unescaped content for strings
     * @param pos  zero-based character offset in the input
     */
    private record Token(Type type, String text, int pos) {
        /**
         * Describes the token for error messages.
         *
         * @return a short human-readable description
         */
        String describe() {
            return switch (type) {
                case EOF -> "end of input";
                case STRING -> "string '" + text.replace("'", "''") + "'";
                case KEYWORD -> "keyword " + text.toUpperCase(Locale.ROOT);
                default -> "'" + text + "'";
            };
        }
    }

    /** Splits SQL text into a list of {@link Token}s terminated by an EOF token. */
    private static final class Lexer {
        private final String in;
        private int i;

        Lexer(String in) {
            this.in = in;
        }

        /**
         * Tokenizes the whole input.
         *
         * @return the tokens, always ending with one EOF token
         * @throws DbException on an illegal character, bad literal or unsupported operator
         */
        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (true) {
                while (i < in.length() && Character.isWhitespace(in.charAt(i))) {
                    i++;
                }
                if (i >= in.length()) {
                    out.add(new Token(Type.EOF, "", in.length()));
                    return out;
                }
                out.add(next());
            }
        }

        private Token next() {
            char c = in.charAt(i);
            int start = i;
            if (isIdentStart(c)) {
                while (i < in.length() && isIdentPart(in.charAt(i))) {
                    i++;
                }
                String word = in.substring(start, i);
                boolean keyword = KEYWORDS.contains(word.toUpperCase(Locale.ROOT));
                return new Token(keyword ? Type.KEYWORD : Type.IDENT, word, start);
            }
            if (isDigit(c) || (c == '-' && i + 1 < in.length() && isDigit(in.charAt(i + 1)))) {
                return integer(start);
            }
            if (c == '\'') {
                return string(start);
            }
            return symbol(c, start);
        }

        private Token integer(int start) {
            i++;
            while (i < in.length() && isDigit(in.charAt(i))) {
                i++;
            }
            if (i < in.length() && isIdentPart(in.charAt(i))) {
                throw syntax("invalid number at position " + start);
            }
            String text = in.substring(start, i);
            try {
                Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw syntax("integer literal out of range at position " + start);
            }
            return new Token(Type.INTEGER, text, start);
        }

        private Token string(int start) {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < in.length()) {
                char c = in.charAt(i++);
                if (c == '\'') {
                    if (i < in.length() && in.charAt(i) == '\'') {
                        sb.append('\'');
                        i++;
                    } else {
                        return new Token(Type.STRING, sb.toString(), start);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw syntax("unterminated string literal starting at position " + start);
        }

        private Token symbol(char c, int start) {
            char following = i + 1 < in.length() ? in.charAt(i + 1) : '\0';
            i++;
            switch (c) {
                case '(', ')', ',', ';', '*', '=' -> {
                    return new Token(Type.SYMBOL, String.valueOf(c), start);
                }
                case '<', '>' -> {
                    if (c == '<' && following == '>') {
                        throw syntax("operator <> is not supported");
                    }
                    if (following == '=') {
                        i++;
                        return new Token(Type.SYMBOL, c + "=", start);
                    }
                    return new Token(Type.SYMBOL, String.valueOf(c), start);
                }
                case '!' -> {
                    if (following == '=') {
                        throw syntax("operator != is not supported");
                    }
                    throw syntax("unexpected character '!' at position " + start);
                }
                default -> throw syntax("unexpected character '" + c + "' at position " + start);
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isIdentStart(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
        }

        private static boolean isIdentPart(char c) {
            return isIdentStart(c) || isDigit(c);
        }
    }

    // ----------------------------------------------------------------- parser

    /** Recursive-descent parser over a token list. */
    private static final class Parser {
        private final List<Token> tokens;
        private int p;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Parses exactly one statement followed by an optional semicolon and end of input.
         *
         * @return the statement
         * @throws DbException on any syntax error
         */
        Statement parseStatement() {
            if (peek().type() == Type.EOF || (isSymbol(";") && tokens.get(p + 1).type() == Type.EOF)) {
                throw syntax("empty statement");
            }
            Statement st = statement();
            if (isSymbol(";")) {
                p++;
            }
            if (peek().type() != Type.EOF) {
                Token t = peek();
                throw syntax("unexpected " + t.describe() + " after end of statement at position " + t.pos());
            }
            return st;
        }

        private Statement statement() {
            Token t = peek();
            if (t.type() != Type.KEYWORD) {
                throw unexpected("a statement keyword (SELECT, INSERT, ...)");
            }
            p++;
            return switch (t.text().toUpperCase(Locale.ROOT)) {
                case "CREATE" -> createTable();
                case "INSERT" -> insert();
                case "SELECT" -> select();
                case "DELETE" -> delete();
                case "UPDATE" -> update();
                case "BEGIN" -> {
                    optionalKeyword("TRANSACTION");
                    yield new Statement.Begin();
                }
                case "START" -> {
                    keyword("TRANSACTION");
                    yield new Statement.Begin();
                }
                case "COMMIT" -> {
                    optionalKeyword("TRANSACTION");
                    yield new Statement.Commit();
                }
                case "ROLLBACK" -> {
                    optionalKeyword("TRANSACTION");
                    yield new Statement.Rollback();
                }
                case "SHOW" -> {
                    keyword("TABLES");
                    yield new Statement.ShowTables();
                }
                default -> {
                    p--;
                    throw unexpected("a statement keyword (SELECT, INSERT, ...)");
                }
            };
        }

        private Statement createTable() {
            keyword("TABLE");
            String table = identifier("table name");
            symbol("(");
            String key = identifier("column name");
            if (!optionalKeyword("INT") && !optionalKeyword("INTEGER")) {
                throw unexpected("INT or INTEGER");
            }
            symbol(",");
            String value = identifier("column name");
            if (!optionalKeyword("VARCHAR") && !optionalKeyword("TEXT")) {
                throw unexpected("VARCHAR or TEXT");
            }
            if (isSymbol("(")) {
                p++;
                Token n = peek();
                long len = integer();
                if (len < 1 || len > MAX_VARCHAR) {
                    throw syntax("VARCHAR length must be between 1 and " + MAX_VARCHAR
                            + " but found " + len + " at position " + n.pos());
                }
                symbol(")");
            }
            symbol(")");
            return new Statement.CreateTable(table, key, value);
        }

        private Statement insert() {
            keyword("INTO");
            String table = identifier("table name");
            keyword("VALUES");
            List<Tuple> tuples = new ArrayList<>();
            do {
                symbol("(");
                long key = integer();
                symbol(",");
                String value = string();
                symbol(")");
                tuples.add(new Tuple(key, value));
            } while (optionalSymbol(","));
            return new Statement.Insert(table, tuples);
        }

        private Statement select() {
            List<String> columns = new ArrayList<>();
            if (!optionalSymbol("*")) {
                do {
                    columns.add(identifier("column name or '*'"));
                } while (optionalSymbol(","));
            }
            keyword("FROM");
            String table = identifier("table name");
            return new Statement.Select(table, columns, whereClause());
        }

        private Statement delete() {
            keyword("FROM");
            String table = identifier("table name");
            return new Statement.Delete(table, whereClause());
        }

        private Statement update() {
            String table = identifier("table name");
            keyword("SET");
            String column = identifier("column name");
            symbol("=");
            String value = string();
            return new Statement.Update(table, column, value, whereClause());
        }

        /**
         * Parses an optional {@code WHERE pred (AND pred)*}.
         *
         * @return the predicates; empty when there is no WHERE clause
         */
        private List<Comparison> whereClause() {
            List<Comparison> preds = new ArrayList<>();
            if (!optionalKeyword("WHERE")) {
                return preds;
            }
            do {
                predicate(preds);
            } while (optionalKeyword("AND"));
            return preds;
        }

        /** Parses one predicate and appends it (BETWEEN appends two comparisons). */
        private void predicate(List<Comparison> out) {
            String column = identifier("column name");
            if (optionalKeyword("BETWEEN")) {
                long lo = integer();
                keyword("AND");
                long hi = integer();
                out.add(new Comparison(column, Op.GE, lo));
                out.add(new Comparison(column, Op.LE, hi));
                return;
            }
            Op op = operator();
            Token t = peek();
            Object literal = switch (t.type()) {
                case INTEGER -> integer();
                case STRING -> string();
                default -> throw unexpected("an integer or string literal");
            };
            out.add(new Comparison(column, op, literal));
        }

        private Op operator() {
            Token t = peek();
            if (t.type() == Type.SYMBOL) {
                Op op = switch (t.text()) {
                    case "=" -> Op.EQ;
                    case "<" -> Op.LT;
                    case "<=" -> Op.LE;
                    case ">" -> Op.GT;
                    case ">=" -> Op.GE;
                    default -> null;
                };
                if (op != null) {
                    p++;
                    return op;
                }
            }
            throw unexpected("a comparison operator (=, <, >, <=, >=) or BETWEEN");
        }

        // ---- token helpers

        private Token peek() {
            return tokens.get(p);
        }

        private boolean isSymbol(String s) {
            Token t = peek();
            return t.type() == Type.SYMBOL && t.text().equals(s);
        }

        private boolean optionalSymbol(String s) {
            if (isSymbol(s)) {
                p++;
                return true;
            }
            return false;
        }

        private void symbol(String s) {
            if (!optionalSymbol(s)) {
                throw unexpected("'" + s + "'");
            }
        }

        private boolean optionalKeyword(String kw) {
            Token t = peek();
            if (t.type() == Type.KEYWORD && t.text().equalsIgnoreCase(kw)) {
                p++;
                return true;
            }
            return false;
        }

        private void keyword(String kw) {
            if (!optionalKeyword(kw)) {
                throw unexpected(kw);
            }
        }

        private String identifier(String what) {
            Token t = peek();
            if (t.type() != Type.IDENT) {
                throw unexpected(what);
            }
            p++;
            return t.text();
        }

        private long integer() {
            Token t = peek();
            if (t.type() != Type.INTEGER) {
                throw unexpected("an integer");
            }
            p++;
            return Long.parseLong(t.text());
        }

        private String string() {
            Token t = peek();
            if (t.type() != Type.STRING) {
                throw unexpected("a string literal");
            }
            p++;
            return t.text();
        }

        private DbException unexpected(String expected) {
            Token t = peek();
            return syntax("expected " + expected + " but found " + t.describe() + " at position " + t.pos());
        }
    }
}
