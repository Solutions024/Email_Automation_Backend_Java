package com.nagad.npsb.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One database session (like an SQLAlchemy Session with autoflush off and expire_on_commit on):
 * rows loaded through it are tracked, new rows are inserted and changed rows updated on flush /
 * commit, and after a commit every row is re-read the next time it is looked up. A transaction is
 * started on first use and ended by commit() / rollback(); close() rolls back anything unfinished.
 *
 * SQL is written by hand (MySQL syntax). The main table of a query is always aliased "t".
 */
public final class Db implements AutoCloseable {
    private Connection conn;
    private final Map<Class<?>, Map<List<Object>, Entity>> identity = new HashMap<>();
    private final List<Entity> pendingNew = new ArrayList<>();
    private final List<Entity> pendingDelete = new ArrayList<>();
    /** Rows inserted / updated in the current transaction (re-read after a rollback). */
    private final Set<Entity> touched = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Entity> insertedNow = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public static Db open() {
        return new Db();
    }

    public static String quote(String name) {
        return "`" + name + "`";
    }

    public Connection connection() {
        try {
            if (conn == null) {
                conn = Database.dataSource().getConnection();
                conn.setAutoCommit(false);
            }
            return conn;
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    // ---------------------------------------------------------------- reading rows

    /** db.get(Model, id): the row with this primary key, or null. */
    public <T extends Entity> T get(Class<T> type, Object... key) {
        if (key.length == 1 && key[0] == null) return null;
        Entity.Meta meta = Entity.meta(type);
        List<Object> k = new ArrayList<>();
        for (Object part : key) k.add(Entity.normalizeKey(part));
        Entity found = identity.computeIfAbsent(type, x -> new HashMap<>()).get(k);
        if (found != null) {
            if (found.expired && !refreshOrForget(found, true)) return null;
            return type.cast(found);
        }
        StringBuilder where = new StringBuilder("WHERE ");
        for (int i = 0; i < meta.keys.size(); i++) {
            if (i > 0) where.append(" AND ");
            where.append("t.").append(quote(meta.columns.get(meta.keys.get(i)))).append(" = ?");
        }
        List<T> rows = all(type, where.toString(), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** SELECT the rows of a model: tail is everything after "FROM table t" (joins, WHERE, ORDER BY...). */
    public <T extends Entity> List<T> all(Class<T> type, String tail, Object... args) {
        Entity.Meta meta = Entity.meta(type);
        String sql = "SELECT " + meta.selectList("t") + " FROM " + quote(meta.table) + " t " + (tail == null ? "" : tail);
        List<T> out = new ArrayList<>();
        try (PreparedStatement st = prepare(sql, args); ResultSet rs = st.executeQuery()) {
            while (rs.next()) out.add(type.cast(hydrate(meta, rs)));
        } catch (SQLException e) {
            throw new DbException(e, sql);
        }
        return out;
    }

    public <T extends Entity> T first(Class<T> type, String tail, Object... args) {
        List<T> rows = all(type, (tail == null ? "" : tail) + " LIMIT 1", args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Entity hydrate(Entity.Meta meta, ResultSet rs) throws SQLException {
        Object[] values = new Object[meta.fields.size()];
        int col = 1;
        for (int i = 0; i < values.length; i++) {
            if (meta.deferred[i]) continue;
            values[i] = read(rs, col++, meta.fields.get(i).getType());
        }
        List<Object> key = new ArrayList<>();
        for (int i : meta.keys) key.add(Entity.normalizeKey(values[i]));
        Map<List<Object>, Entity> byKey = identity.computeIfAbsent(meta.type, x -> new HashMap<>());
        Entity existing = byKey.get(key);
        if (existing != null) {
            if (existing.expired) apply(meta, existing, values, true);
            return existing;
        }
        Entity e;
        try {
            e = (Entity) meta.type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        apply(meta, e, values, false);
        e.db = this;
        byKey.put(key, e);
        return e;
    }

    /** Put database values into a row. keepLocal: fields changed in code since the last commit keep their
     *  new value (they are still written at the next flush), like an expired SQLAlchemy object. */
    private void apply(Entity.Meta meta, Entity e, Object[] values, boolean keepLocal) {
        for (int i = 0; i < values.length; i++) {
            if (meta.deferred[i]) continue;
            boolean changedHere = keepLocal && e.snapshot != null && !Entity.same(meta.get(e, i), e.snapshot[i]);
            if (!changedHere) meta.set(e, i, values[i]);
        }
        e.snapshot = values.clone();
        e.expired = false;
        e.related.clear();
    }

    private static Object read(ResultSet rs, int i, Class<?> type) throws SQLException {
        if (type == String.class) return rs.getString(i);
        if (type == Integer.class) { int v = rs.getInt(i); return rs.wasNull() ? null : v; }
        if (type == Long.class) { long v = rs.getLong(i); return rs.wasNull() ? null : v; }
        if (type == Boolean.class) { boolean v = rs.getBoolean(i); return rs.wasNull() ? null : v; }
        if (type == BigDecimal.class) return rs.getBigDecimal(i);
        if (type == byte[].class) return rs.getBytes(i);
        if (type == LocalDateTime.class) {
            try {
                return rs.getObject(i, LocalDateTime.class);
            } catch (SQLException | RuntimeException e) {
                Timestamp ts = rs.getTimestamp(i);
                return ts == null ? null : ts.toLocalDateTime();
            }
        }
        if (type == LocalDate.class) {
            try {
                return rs.getObject(i, LocalDate.class);
            } catch (SQLException | RuntimeException e) {
                java.sql.Date d = rs.getDate(i);
                return d == null ? null : d.toLocalDate();
            }
        }
        return rs.getObject(i);
    }

    /** db.refresh(row): read its columns again. */
    public void refresh(Entity e) {
        if (!refreshOrForget(e, false)) throw new DbException(new SQLException("Row no longer exists: " + e.getClass().getSimpleName()));
    }

    private boolean refreshOrForget(Entity e, boolean keepLocal) {
        Entity.Meta meta = Entity.meta(e.getClass());
        StringBuilder sql = new StringBuilder("SELECT ").append(meta.selectList("t")).append(" FROM ")
                .append(quote(meta.table)).append(" t WHERE ");
        List<Object> key = keyFromSnapshot(meta, e);
        for (int i = 0; i < meta.keys.size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append("t.").append(quote(meta.columns.get(meta.keys.get(i)))).append(" = ?");
        }
        try (PreparedStatement st = prepare(sql.toString(), key.toArray()); ResultSet rs = st.executeQuery()) {
            if (!rs.next()) {
                forget(e);
                return false;
            }
            Object[] values = new Object[meta.fields.size()];
            int col = 1;
            for (int i = 0; i < values.length; i++) {
                if (meta.deferred[i]) continue;
                values[i] = read(rs, col++, meta.fields.get(i).getType());
            }
            apply(meta, e, values, keepLocal);
            return true;
        } catch (SQLException ex) {
            throw new DbException(ex, sql.toString());
        }
    }

    private List<Object> keyFromSnapshot(Entity.Meta meta, Entity e) {
        List<Object> key = new ArrayList<>();
        for (int i : meta.keys) key.add(e.snapshot != null ? e.snapshot[i] : meta.get(e, i));
        return key;
    }

    private void forget(Entity e) {
        Entity.Meta meta = Entity.meta(e.getClass());
        Map<List<Object>, Entity> byKey = identity.get(meta.type);
        if (byKey != null) {
            List<Object> key = new ArrayList<>();
            for (Object k : keyFromSnapshot(meta, e)) key.add(Entity.normalizeKey(k));
            byKey.remove(key);
        }
        e.snapshot = null;
        e.expired = false;
    }

    // ---------------------------------------------------------------- changing rows

    /** db.add(row): inserted at the next flush / commit. */
    public void add(Entity e) {
        if (e.db == this && e.snapshot != null) return;  // already in this session
        for (Entity p : pendingNew) if (p == e) return;
        e.db = this;
        pendingNew.add(e);
    }

    /** db.delete(row): deleted at the next flush / commit. */
    public void delete(Entity e) {
        if (pendingNew.removeIf(p -> p == e)) return;
        if (e.snapshot == null) return;
        for (Entity p : pendingDelete) if (p == e) return;
        pendingDelete.add(e);
    }

    /** Is this row part of this session (added, or loaded and not deleted)? */
    public boolean contains(Entity e) {
        if (e.db != this) return false;
        for (Entity p : pendingNew) if (p == e) return true;
        return e.snapshot != null;
    }

    /** Write pending inserts, updates and deletes (the transaction stays open). */
    public void flush() {
        // inserts, in the order the rows were added
        List<Entity> inserts = new ArrayList<>(pendingNew);
        pendingNew.clear();
        for (Entity e : inserts) insert(e);
        // updates of changed rows
        for (Map<List<Object>, Entity> byKey : identity.values()) {
            for (Entity e : new ArrayList<>(byKey.values())) {
                if (e.snapshot == null || pendingDelete.contains(e)) continue;
                update(e);
            }
        }
        // deletes
        List<Entity> deletes = new ArrayList<>(pendingDelete);
        pendingDelete.clear();
        for (Entity e : deletes) deleteNow(e);
    }

    private void insert(Entity e) {
        Entity.Meta meta = Entity.meta(e.getClass());
        Object[] values = meta.values(e);
        List<String> cols = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            boolean autoKeyColumn = meta.autoKey && meta.keys.contains(i);
            if (autoKeyColumn && values[i] == null) continue;
            cols.add(quote(meta.columns.get(i)));
            args.add(values[i]);
        }
        String sql = "INSERT INTO " + quote(meta.table) + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
        try (PreparedStatement st = connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(st, args.toArray());
            st.executeUpdate();
            if (meta.autoKey) {
                int keyIndex = meta.keys.get(0);
                if (values[keyIndex] == null) {
                    try (ResultSet keys = st.getGeneratedKeys()) {
                        if (keys.next()) {
                            Object generated = keys.getLong(1);
                            Class<?> t = meta.fields.get(keyIndex).getType();
                            meta.set(e, keyIndex, t == Integer.class ? (Object) (int) (long) (Long) generated : generated);
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            throw new DbException(ex, sql);
        }
        e.snapshot = meta.values(e);
        e.expired = false;
        e.db = this;
        identity.computeIfAbsent(meta.type, x -> new HashMap<>()).put(meta.key(e), e);
        touched.add(e);
        insertedNow.add(e);
    }

    private void update(Entity e) {
        Entity.Meta meta = Entity.meta(e.getClass());
        if (changedColumns(meta, e).isEmpty()) return;
        e.beforeUpdate();
        List<Integer> changed = changedColumns(meta, e);
        StringBuilder sql = new StringBuilder("UPDATE ").append(quote(meta.table)).append(" SET ");
        List<Object> args = new ArrayList<>();
        for (int n = 0; n < changed.size(); n++) {
            int i = changed.get(n);
            if (n > 0) sql.append(", ");
            sql.append(quote(meta.columns.get(i))).append(" = ?");
            args.add(meta.get(e, i));
        }
        sql.append(" WHERE ");
        List<Object> key = keyFromSnapshot(meta, e);
        for (int n = 0; n < meta.keys.size(); n++) {
            if (n > 0) sql.append(" AND ");
            sql.append(quote(meta.columns.get(meta.keys.get(n)))).append(" = ?");
            args.add(key.get(n));
        }
        execute(sql.toString(), args.toArray());
        Object[] values = meta.values(e);
        for (int i = 0; i < values.length; i++) if (meta.deferred[i]) values[i] = e.snapshot[i];
        e.snapshot = values;
        touched.add(e);
    }

    private static List<Integer> changedColumns(Entity.Meta meta, Entity e) {
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < meta.fields.size(); i++) {
            if (meta.deferred[i]) continue;
            if (!Entity.same(meta.get(e, i), e.snapshot[i])) changed.add(i);
        }
        return changed;
    }

    private void deleteNow(Entity e) {
        Entity.Meta meta = Entity.meta(e.getClass());
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(quote(meta.table)).append(" WHERE ");
        List<Object> key = keyFromSnapshot(meta, e);
        for (int n = 0; n < meta.keys.size(); n++) {
            if (n > 0) sql.append(" AND ");
            sql.append(quote(meta.columns.get(meta.keys.get(n)))).append(" = ?");
        }
        execute(sql.toString(), key.toArray());
        forget(e);
    }

    /** Flush and commit. Afterwards every row is re-read the next time it is looked up. */
    public void commit() {
        flush();
        try {
            if (conn != null) conn.commit();
        } catch (SQLException e) {
            throw new DbException(e);
        }
        release();
        touched.clear();
        insertedNow.clear();
        for (Map<List<Object>, Entity> byKey : identity.values()) {
            for (Entity e : byKey.values()) {
                e.expired = true;
                e.related.clear();
            }
        }
    }

    /** Undo the transaction: pending rows are dropped, changed rows get their database values back. */
    public void rollback() {
        try {
            if (conn != null) conn.rollback();
        } catch (SQLException e) {
            throw new DbException(e);
        }
        release();
        for (Entity e : pendingNew) e.db = null;
        pendingNew.clear();
        pendingDelete.clear();
        for (Entity e : new ArrayList<>(insertedNow)) forget(e);  // never made it to the database
        List<Entity> reload = new ArrayList<>();
        for (Map<List<Object>, Entity> byKey : identity.values()) {
            for (Entity e : byKey.values()) {
                if (touched.contains(e) || !changedColumns(Entity.meta(e.getClass()), e).isEmpty()) reload.add(e);
                e.related.clear();
            }
        }
        touched.clear();
        insertedNow.clear();
        for (Entity e : reload) refreshOrForget(e, false);
        try {
            if (conn != null) conn.rollback();  // the reads above started a new transaction
        } catch (SQLException e) {
            throw new DbException(e);
        }
        release();
    }

    private void release() {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // the pool replaces a broken connection
            }
            conn = null;
        }
    }

    @Override
    public void close() {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // closing anyway
            }
            release();
        }
        identity.clear();
        pendingNew.clear();
        pendingDelete.clear();
    }

    // ---------------------------------------------------------------- plain SQL

    /** INSERT / UPDATE / DELETE: returns the number of rows changed. Loaded rows are not updated. */
    public int execute(String sql, Object... args) {
        try (PreparedStatement st = prepare(sql, args)) {
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new DbException(e, sql);
        }
    }

    /** The first column of the first row (null when there is no row). */
    public Object scalar(String sql, Object... args) {
        try (PreparedStatement st = prepare(sql, args); ResultSet rs = st.executeQuery()) {
            return rs.next() ? rs.getObject(1) : null;
        } catch (SQLException e) {
            throw new DbException(e, sql);
        }
    }

    public long count(String sql, Object... args) {
        Object v = scalar(sql, args);
        return v == null ? 0 : ((Number) v).longValue();
    }

    public byte[] bytes(String sql, Object... args) {
        try (PreparedStatement st = prepare(sql, args); ResultSet rs = st.executeQuery()) {
            return rs.next() ? rs.getBytes(1) : null;
        } catch (SQLException e) {
            throw new DbException(e, sql);
        }
    }

    /** All rows, each as an array of column values (dates as LocalDateTime). */
    public List<Object[]> rows(String sql, Object... args) {
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement st = prepare(sql, args); ResultSet rs = st.executeQuery()) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) {
                    Object v = rs.getObject(i + 1);
                    if (v instanceof Timestamp ts) v = ts.toLocalDateTime();
                    else if (v instanceof java.sql.Date d) v = d.toLocalDate();
                    else if (v instanceof java.sql.Clob clob) v = clob.getSubString(1, (int) clob.length());
                    row[i] = v;
                }
                out.add(row);
            }
        } catch (SQLException e) {
            throw new DbException(e, sql);
        }
        return out;
    }

    /** The first column of every row. */
    public List<Object> column(String sql, Object... args) {
        List<Object> out = new ArrayList<>();
        for (Object[] row : rows(sql, args)) out.add(row[0]);
        return out;
    }

    public List<Long> ids(String sql, Object... args) {
        List<Long> out = new ArrayList<>();
        for (Object v : column(sql, args)) out.add(v == null ? null : ((Number) v).longValue());
        return out;
    }

    private PreparedStatement prepare(String sql, Object[] args) throws SQLException {
        PreparedStatement st = connection().prepareStatement(sql);
        bind(st, args);
        return st;
    }

    private static void bind(PreparedStatement st, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object v = args[i];
            if (v instanceof LocalDateTime d) st.setTimestamp(i + 1, Timestamp.valueOf(d));
            else if (v instanceof LocalDate d) st.setDate(i + 1, java.sql.Date.valueOf(d));
            else if (v instanceof Boolean b) st.setBoolean(i + 1, b);
            else if (v instanceof byte[] b) st.setBytes(i + 1, b);
            else if (v instanceof BigDecimal b) st.setBigDecimal(i + 1, b);
            else if (v == null) st.setObject(i + 1, null);
            else st.setObject(i + 1, v);
        }
    }

    /** "?, ?, ?" for an IN (...) list, and its values. Empty lists become IN (NULL): nothing matches. */
    public static String in(Collection<?> values) {
        if (values.isEmpty()) return "(NULL)";
        return "(" + String.join(", ", java.util.Collections.nCopies(values.size(), "?")) + ")";
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

    /** Group helper: values of a two-column query as a map. */
    public Map<Object, Object> pairs(String sql, Object... args) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Object[] row : rows(sql, args)) out.put(row[0], row[1]);
        return out;
    }

    public static final class DbException extends RuntimeException {
        public final SQLException sql;

        DbException(SQLException e) {
            super(e.getMessage(), e);
            this.sql = e;
        }

        DbException(SQLException e, String statement) {
            super(e.getMessage() + " [" + statement + "]", e);
            this.sql = e;
        }

        /** A unique / foreign key constraint failed (IntegrityError). */
        public boolean integrity() {
            String state = sql.getSQLState();
            return state != null && state.startsWith("23");
        }
    }
}
