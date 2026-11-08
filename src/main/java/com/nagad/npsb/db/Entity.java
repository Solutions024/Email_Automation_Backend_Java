package com.nagad.npsb.db;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class of the database rows (models package). Works like an SQLAlchemy mapped object:
 * loaded rows are tracked by their Db, changed fields are written on flush / commit, and related
 * rows are loaded on first use (and cached until the next commit).
 */
public abstract class Entity {
    /** The session this row belongs to (null: not added yet). */
    transient Db db;
    /** Column values as last read from / written to the database (null: not in the database yet). */
    transient Object[] snapshot;
    /** Re-read from the database the next time it is looked up (after a commit, like expire_on_commit). */
    transient boolean expired;
    /** Related rows loaded so far (cleared at commit). */
    transient Map<String, Object> related = new HashMap<>();

    /** Called before an UPDATE is written (SQLAlchemy onupdate). */
    protected void beforeUpdate() {}

    /** Called once the row's INSERT (inserted) or UPDATE was committed: used for the application log (AppLog). */
    protected void afterCommit(boolean inserted) {}

    public final Db session() { return db; }

    /** True once the row is in the database. */
    public final boolean persisted() { return snapshot != null; }

    // ---------------------------------------------------------------- related rows

    @SuppressWarnings("unchecked")
    protected final <T extends Entity> T one(String name, Class<T> type, Object id) {
        if (id == null) return null;
        if (db == null) throw new IllegalStateException(getClass().getSimpleName() + " is not in a session");
        return db.get(type, id);
    }

    @SuppressWarnings("unchecked")
    protected final <T extends Entity> List<T> many(String name, Class<T> type, String where, Object... args) {
        Object cached = related.get(name);
        if (cached != null) return (List<T>) cached;
        if (db == null) throw new IllegalStateException(getClass().getSimpleName() + " is not in a session");
        List<T> rows = snapshot == null ? new ArrayList<>() : db.all(type, where, args);
        related.put(name, rows);
        return rows;
    }

    protected final void setMany(String name, List<? extends Entity> rows) {
        related.put(name, rows);
    }

    // ---------------------------------------------------------------- metadata

    public static final class Meta {
        public final Class<?> type;
        public final String table;
        public final List<Field> fields = new ArrayList<>();   // every column, in declaration order
        public final List<String> columns = new ArrayList<>();
        public final List<Integer> keys = new ArrayList<>();   // indexes into fields
        public final boolean autoKey;
        public final boolean[] deferred;

        Meta(Class<?> type) {
            this.type = type;
            Table t = type.getAnnotation(Table.class);
            if (t == null) throw new IllegalStateException(type + " has no @Table");
            table = t.value();
            boolean auto = false;
            List<Boolean> lazy = new ArrayList<>();
            for (Field f : type.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                f.setAccessible(true);
                Col col = f.getAnnotation(Col.class);
                Id id = f.getAnnotation(Id.class);
                fields.add(f);
                columns.add(col != null ? col.value() : snake(f.getName()));
                lazy.add(f.isAnnotationPresent(Deferred.class));
                if (id != null) {
                    keys.add(fields.size() - 1);
                    auto = auto || id.auto();
                }
            }
            autoKey = auto && keys.size() == 1;
            deferred = new boolean[lazy.size()];
            for (int i = 0; i < lazy.size(); i++) deferred[i] = lazy.get(i);
            if (keys.isEmpty()) throw new IllegalStateException(type + " has no @Id");
        }

        Object get(Entity e, int i) {
            try {
                return fields.get(i).get(e);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }

        void set(Entity e, int i, Object value) {
            try {
                fields.get(i).set(e, value);
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }

        Object[] values(Entity e) {
            Object[] out = new Object[fields.size()];
            for (int i = 0; i < out.length; i++) out[i] = get(e, i);
            return out;
        }

        List<Object> key(Entity e) {
            List<Object> k = new ArrayList<>(keys.size());
            for (int i : keys) k.add(normalizeKey(get(e, i)));
            return k;
        }

        /** The SELECT list (deferred columns left out). */
        String selectList(String alias) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (deferred[i]) continue;
                if (b.length() > 0) b.append(", ");
                b.append(alias).append('.').append(Db.quote(columns.get(i)));
            }
            return b.toString();
        }
    }

    static Object normalizeKey(Object v) {
        return v instanceof Number n ? (Object) n.longValue() : v;
    }

    private static final Map<Class<?>, Meta> METAS = new ConcurrentHashMap<>();

    public static Meta meta(Class<?> type) {
        return METAS.computeIfAbsent(type, Meta::new);
    }

    static String snake(String camel) {
        StringBuilder b = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) b.append('_').append(Character.toLowerCase(c));
            else b.append(c);
        }
        return b.toString();
    }

    static boolean same(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        if (a instanceof Number x && b instanceof Number y && !(a instanceof BigDecimal) && !(b instanceof BigDecimal)) {
            return x.longValue() == y.longValue();
        }
        return Objects.equals(a, b);
    }
}
