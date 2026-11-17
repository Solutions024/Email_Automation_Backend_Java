package com.nagad.npsb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CREATE DATABASE can't take the name as a ? parameter, so only plain names may reach the SQL. */
class DataSourceConfigTest {

    @Test
    void plainNamesAreQuoted() {
        assertEquals("CREATE DATABASE IF NOT EXISTS `npsb_dispute` CHARACTER SET utf8mb4",
                DataSourceConfig.createDatabaseSql("npsb_dispute"));
        assertEquals("CREATE DATABASE IF NOT EXISTS `npsb-test$2` CHARACTER SET utf8mb4",
                DataSourceConfig.createDatabaseSql("npsb-test$2"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "npsb`; DROP DATABASE mysql; -- ",   // breaking out of the backticks
            "npsb`",
            "npsb dispute",                      // whitespace
            "npsb;",
            "npsb'",
            "npsb\"",
            "npsb/*x*/",
            "npsb\n",
            "npsbé",                             // non-ASCII
            "a12345678901234567890123456789012345678901234567890123456789012345", // 66 characters, over MySQL's 64
    })
    void anythingElseIsRefused(String name) {
        assertThrows(IllegalStateException.class, () -> DataSourceConfig.createDatabaseSql(name));
    }
}
