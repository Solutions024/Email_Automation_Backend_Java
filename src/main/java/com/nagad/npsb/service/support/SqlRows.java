package com.nagad.npsb.service.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain SQL results the way the old Db session gave them (db.rows / scalar / count / column): each row as an array of
 * column values, with dates as LocalDateTime / LocalDate and CLOBs as text. Positional "?" parameters.
 * Runs in the current transaction, if any (JdbcClient shares the JPA transaction's connection).
 */
public final class SqlRows {
    private SqlRows() {}

    /** All rows, each as an array of column values (dates as LocalDateTime). */
    public static List<Object[]> rows(JdbcClient jdbc, String sql, Object... args) {
        return jdbc.sql(sql).params(args).query((rs, n) -> {
            int count = rs.getMetaData().getColumnCount();
            Object[] row = new Object[count];
            for (int i = 0; i < count; i++) row[i] = value(rs.getObject(i + 1));
            return row;
        }).list();
    }

    /** The first column of the first row (null when there is no row). */
    public static Object scalar(JdbcClient jdbc, String sql, Object... args) {
        List<Object[]> rows = rows(jdbc, sql, args);
        return rows.isEmpty() ? null : rows.get(0)[0];
    }

    /** A COUNT(*) (whatever number type the database answers with). */
    public static long count(JdbcClient jdbc, String sql, Object... args) {
        Object v = scalar(jdbc, sql, args);
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** The first column of every row. */
    public static List<Object> column(JdbcClient jdbc, String sql, Object... args) {
        List<Object> out = new ArrayList<>();
        for (Object[] row : rows(jdbc, sql, args)) out.add(row[0]);
        return out;
    }

    private static Object value(Object v) throws SQLException {
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof Clob clob) return clob.getSubString(1, (int) clob.length());
        return v;
    }
}
