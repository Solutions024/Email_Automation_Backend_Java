package com.nagad.npsb.config;

import java.util.regex.Pattern;

/**
 * The schema statements SchemaInitializer runs. Table, column and index names can't be ? parameters, so every name
 * is checked to be a plain identifier and every column type to be plain SQL type text before it is put in a statement.
 * The names come from SchemaInitializer's own lists (and index names read from the database), never from a request.
 */
final class Ddl {
    private Ddl() {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    /** e.g. VARCHAR(255) NOT NULL DEFAULT 'x', BOOLEAN NOT NULL DEFAULT FALSE: no quotes other than '...', no ; or comments. */
    private static final Pattern TYPE = Pattern.compile("[A-Za-z0-9_ (),.]+(?:'[A-Za-z0-9_ .-]*'[A-Za-z0-9_ (),.]*)?");

    static String name(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("Not a plain SQL name: " + name);
        return name;
    }

    static String type(String type) {
        if (type == null || !TYPE.matcher(type).matches() || type.contains("--")) {
            throw new IllegalArgumentException("Not a plain SQL column type: " + type);
        }
        return type;
    }

    static String createIndex(String table, String column) {
        return "CREATE INDEX " + name("ix_" + table + "_" + column) + " ON " + name(table) + " (" + name(column) + ")";
    }

    static String addColumn(String table, String column, String type) {
        return "ALTER TABLE " + name(table) + " ADD COLUMN `" + name(column) + "` " + type(type);
    }

    static String modifyColumn(String table, String column, String type) {
        return "ALTER TABLE " + name(table) + " MODIFY " + name(column) + " " + type(type);
    }

    static String dropColumn(String table, String column) {
        return "ALTER TABLE " + name(table) + " DROP COLUMN " + name(column);
    }

    static String dropIndex(String table, String index) {
        return "ALTER TABLE " + name(table) + " DROP INDEX `" + name(index) + "`";
    }
}
