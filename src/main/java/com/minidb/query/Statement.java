package com.minidb.query;

import java.util.List;
import java.util.Objects;

/**
 * Abstract syntax tree of one parsed SQL statement, produced by {@link SqlParser} and
 * consumed by {@link QueryExecutor}.
 */
public sealed interface Statement {

    /** Comparison operators supported in WHERE clauses. */
    enum Op {
        /** {@code =} */
        EQ,
        /** {@code <} */
        LT,
        /** {@code <=} */
        LE,
        /** {@code >} */
        GT,
        /** {@code >=} */
        GE
    }

    /**
     * One predicate {@code column op literal}. Predicates in a WHERE list are joined by AND.
     *
     * @param column column name as written (case preserved)
     * @param op     comparison operator
     * @param value  literal: a {@link Long} or a {@link String}
     */
    record Comparison(String column, Op op, Object value) {
        /**
         * Validates the predicate.
         *
         * @param column column name
         * @param op     operator
         * @param value  literal (Long or String)
         */
        public Comparison {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(op, "op");
            if (!(value instanceof Long) && !(value instanceof String)) {
                throw new IllegalArgumentException("literal must be Long or String: " + value);
            }
        }
    }

    /**
     * One {@code (key, value)} tuple of an INSERT statement.
     *
     * @param key   integer literal (validated against the int range by the executor)
     * @param value string literal
     */
    record Tuple(long key, String value) {
        /**
         * Validates the tuple.
         *
         * @param key   integer literal
         * @param value string literal
         */
        public Tuple {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * {@code CREATE TABLE table (keyColumn INT, valueColumn VARCHAR)}.
     *
     * @param table       table name
     * @param keyColumn   name of the INT primary-key column
     * @param valueColumn name of the VARCHAR column
     */
    record CreateTable(String table, String keyColumn, String valueColumn) implements Statement { }

    /**
     * {@code INSERT INTO table VALUES (k, 'v'), ...}.
     *
     * @param table  table name
     * @param tuples one or more tuples (immutable copy)
     */
    record Insert(String table, List<Tuple> tuples) implements Statement {
        /**
         * Copies the tuple list.
         *
         * @param table  table name
         * @param tuples tuples
         */
        public Insert {
            tuples = List.copyOf(tuples);
        }
    }

    /**
     * {@code SELECT * | cols FROM table [WHERE ...]}.
     *
     * @param table   table name
     * @param columns projected columns; empty means {@code *}
     * @param where   predicates joined by AND; empty means no filter
     */
    record Select(String table, List<String> columns, List<Comparison> where) implements Statement {
        /**
         * Copies the lists.
         *
         * @param table   table name
         * @param columns projected columns
         * @param where   predicates
         */
        public Select {
            columns = List.copyOf(columns);
            where = List.copyOf(where);
        }
    }

    /**
     * {@code DELETE FROM table [WHERE ...]}.
     *
     * @param table table name
     * @param where predicates joined by AND; empty means delete all rows
     */
    record Delete(String table, List<Comparison> where) implements Statement {
        /**
         * Copies the list.
         *
         * @param table table name
         * @param where predicates
         */
        public Delete {
            where = List.copyOf(where);
        }
    }

    /**
     * {@code UPDATE table SET column = 'value' [WHERE ...]}.
     *
     * @param table  table name
     * @param column column being assigned
     * @param value  new string value
     * @param where  predicates joined by AND; empty means all rows
     */
    record Update(String table, String column, String value, List<Comparison> where) implements Statement {
        /**
         * Copies the list.
         *
         * @param table  table name
         * @param column assigned column
         * @param value  new value
         * @param where  predicates
         */
        public Update {
            where = List.copyOf(where);
        }
    }

    /** {@code BEGIN [TRANSACTION]} / {@code START TRANSACTION}. */
    record Begin() implements Statement { }

    /** {@code COMMIT [TRANSACTION]}. */
    record Commit() implements Statement { }

    /** {@code ROLLBACK [TRANSACTION]}. */
    record Rollback() implements Statement { }

    /** {@code SHOW TABLES}. */
    record ShowTables() implements Statement { }
}
