package com.nagad.npsb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Names and types can't be ? parameters in schema statements, so only plain SQL text may reach them. */
class DdlTest {

    @Test
    void plainNamesAndTypesMakeTheExpectedStatements() {
        assertEquals("CREATE INDEX ix_users_email ON users (email)", Ddl.createIndex("users", "email"));
        assertEquals("ALTER TABLE users ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT 'user'",
                Ddl.addColumn("users", "role", "VARCHAR(20) NOT NULL DEFAULT 'user'"));
        assertEquals("ALTER TABLE transactions MODIFY txn_type VARCHAR(80) NOT NULL",
                Ddl.modifyColumn("transactions", "txn_type", "VARCHAR(80) NOT NULL"));
        assertEquals("ALTER TABLE partners DROP COLUMN institution_code", Ddl.dropColumn("partners", "institution_code"));
        assertEquals("ALTER TABLE email_dispatches DROP INDEX `ux_old`", Ddl.dropIndex("email_dispatches", "ux_old"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "ux_old`; DROP TABLE users; -- ",   // index name read from the database, breaking out of the backticks
            "users;",
            "users dispute",
            "users'",
            "users\"",
            "users/*x*/",
            "users\n",
            "1users",                            // must start with a letter or _
            "usérs",
            "a1234567890123456789012345678901234567890123456789012345678901234", // 65 characters
    })
    void otherNamesAreRefused(String name) {
        assertThrows(IllegalArgumentException.class, () -> Ddl.name(name));
        assertThrows(IllegalArgumentException.class, () -> Ddl.dropIndex("email_dispatches", name));
        assertThrows(IllegalArgumentException.class, () -> Ddl.dropColumn("users", name));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "VARCHAR(20); DROP TABLE users",
            "VARCHAR(20) -- comment",
            "VARCHAR(20) /* comment */",
            "VARCHAR(20) DEFAULT 'a'; DROP TABLE users; --'",
            "VARCHAR(20) DEFAULT 'it''s'",       // escaped quote inside the default
            "VARCHAR(20) DEFAULT \"x\"",
            "VARCHAR(20) DEFAULT `x`",
            "VARCHAR(20)\nNOT NULL",
    })
    void otherTypesAreRefused(String type) {
        assertThrows(IllegalArgumentException.class, () -> Ddl.type(type));
        assertThrows(IllegalArgumentException.class, () -> Ddl.addColumn("users", "role", type));
    }
}
