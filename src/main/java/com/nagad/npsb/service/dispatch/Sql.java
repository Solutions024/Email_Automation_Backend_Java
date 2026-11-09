package com.nagad.npsb.service.dispatch;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Table;
import jakarta.persistence.TypedQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The hand-written SQL of the email pages (MySQL syntax; the main table of a query is always aliased "t"):
 * rows of an entity selected with a WHERE / ORDER BY tail, and report queries (counts, ids, rows) run with JdbcClient.
 *
 * Entity queries return the rows managed by the current persistence context (rows already loaded keep their
 * in-memory state). Nothing is flushed before a query (flush mode COMMIT), exactly as before. Plain SQL runs on the
 * connection of the current transaction, so it sees what the transaction has flushed; UPDATE / DELETE must run
 * inside a transaction.
 */
@Component
public class Sql {
    private final EntityManager em;
    private final JdbcClient jdbc;

    public Sql(EntityManager em, JdbcClient jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- entities

    /** SELECT the rows of an entity: tail is everything after "FROM table t" (joins, WHERE, ORDER BY, LIMIT). */
    @SuppressWarnings("unchecked")
    public <T> List<T> all(Class<T> type, String tail, Object... args) {
        Table table = type.getAnnotation(Table.class);
        if (table == null) throw new IllegalArgumentException(type + " has no @Table");
        String sql = "SELECT t.* FROM " + table.name() + " t " + (tail == null ? "" : tail);
        Query query = em.createNativeQuery(ordinal(sql), type);
        for (int i = 0; i < args.length; i++) query.setParameter(i + 1, bindable(args[i]));
        return query.getResultList();
    }

    /** The first row of all(...), or null. */
    public <T> T first(Class<T> type, String tail, Object... args) {
        List<T> rows = all(type, (tail == null ? "" : tail) + " LIMIT 1", args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A JPQL query with ?1, ?2... parameters; limit &lt;= 0: no limit. */
    public <T> List<T> jpql(Class<T> type, String jpql, int limit, Object... args) {
        TypedQuery<T> query = em.createQuery(jpql, type);
        for (int i = 0; i < args.length; i++) query.setParameter(i + 1, args[i]);
        if (limit > 0) query.setMaxResults(limit);
        return query.getResultList();
    }

    // ---------------------------------------------------------------- plain SQL

    /** All rows, each as an array of column values (dates as LocalDateTime / LocalDate, CLOBs as text). */
    public List<Object[]> rows(String sql, Object... args) {
        return jdbc.sql(sql).params(bindable(args)).query((rs, n) -> row(rs)).list();
    }

    /** The first column of the first row (null when there is no row). */
    public Object scalar(String sql, Object... args) {
        List<Object[]> rows = rows(sql, args);
        return rows.isEmpty() ? null : rows.get(0)[0];
    }

    public long count(String sql, Object... args) {
        Object v = scalar(sql, args);
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** The first column of every row, as numbers. */
    public List<Long> ids(String sql, Object... args) {
        List<Long> out = new ArrayList<>();
        for (Object[] row : rows(sql, args)) out.add(row[0] == null ? null : ((Number) row[0]).longValue());
        return out;
    }

    /** INSERT / UPDATE / DELETE (inside a transaction): the number of rows changed. Loaded entities are not updated. */
    public int execute(String sql, Object... args) {
        return jdbc.sql(sql).params(bindable(args)).update();
    }

    /** "(?, ?, ?)" for an IN list. An empty list becomes IN (NULL): nothing matches. */
    public static String in(Collection<?> values) {
        if (values.isEmpty()) return "(NULL)";
        return "(" + String.join(", ", Collections.nCopies(values.size(), "?")) + ")";
    }

    /** Concatenate arguments: plain values and collections (for IN lists) in order. */
    public static Object[] args(Object... parts) {
        List<Object> out = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof Collection<?> c) out.addAll(c);
            else out.add(p);
        }
        return out.toArray();
    }

    // ---------------------------------------------------------------- helpers

    /** "?" placeholders numbered for a native JPA query: "?1", "?2"... */
    private static String ordinal(String sql) {
        StringBuilder b = new StringBuilder(sql.length() + 16);
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            b.append(c);
            if (c == '?') b.append(++n);
        }
        return b.toString();
    }

    /** Dates bound as JDBC timestamps / dates, as the old database layer bound them. */
    private static Object bindable(Object v) {
        if (v instanceof LocalDateTime d) return Timestamp.valueOf(d);
        if (v instanceof LocalDate d) return java.sql.Date.valueOf(d);
        return v;
    }

    private static Object[] bindable(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) out[i] = bindable(args[i]);
        return out;
    }

    private static Object[] row(ResultSet rs) throws SQLException {
        int n = rs.getMetaData().getColumnCount();
        Object[] row = new Object[n];
        for (int i = 0; i < n; i++) {
            Object v = rs.getObject(i + 1);
            if (v instanceof Timestamp ts) v = ts.toLocalDateTime();
            else if (v instanceof java.sql.Date d) v = d.toLocalDate();
            else if (v instanceof Clob clob) v = clob.getSubString(1, (int) clob.length());
            row[i] = v;
        }
        return row;
    }
}
