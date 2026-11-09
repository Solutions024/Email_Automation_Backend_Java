package com.nagad.npsb.service.dispatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A query on a table aliased "t": the SQL after "FROM <table> t" and its arguments. */
public record SqlQuery(String sql, List<Object> args) {

    /** The same query with one more condition (and its arguments). */
    public SqlQuery and(String condition, Object... more) {
        List<Object> a = new ArrayList<>(args);
        a.addAll(Arrays.asList(more));
        return new SqlQuery(sql + " AND " + condition, a);
    }

    public Object[] argArray() {
        return args.toArray();
    }
}
