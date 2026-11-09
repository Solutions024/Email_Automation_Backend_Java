package com.nagad.npsb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The tables: created when missing, and older tables brought up to date (columns added later, columns no longer
 * used, wider columns...), exactly as the Python backend did, so both can use the same database.
 * Runs once at startup, before JPA uses the database (DataSourceConfig). Hibernate never changes the schema
 * (spring.jpa.hibernate.ddl-auto=none).
 */
public final class SchemaInitializer {
    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

    /** MySQL / MariaDB (false: H2 or another database, e.g. the tests). */
    private final boolean mysql;

    public SchemaInitializer(boolean mysql) {
        this.mysql = mysql;
    }

    /** Every table, in creation order (a table after the ones it refers to). Same as models.py / schema.sql. */
    private static final String[][] TABLES = {
        {"users", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            username VARCHAR(30) NOT NULL,
            email VARCHAR(254) NULL,
            full_name VARCHAR(100) NOT NULL,
            `role` VARCHAR(10) NOT NULL,
            email_verified BOOL NOT NULL,
            email_verified_at DATETIME NULL,
            pending_email VARCHAR(254) NULL,
            password_hash VARCHAR(200) NOT NULL,
            is_active BOOL NOT NULL,
            token_version INTEGER NOT NULL,
            failed_login_count INTEGER NOT NULL,
            locked_until DATETIME NULL,
            last_login_at DATETIME NULL,
            password_changed_at DATETIME NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            UNIQUE (username),
            UNIQUE (email)""", ""},
        {"trusted_devices", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            user_id INTEGER NOT NULL,
            token_hash VARCHAR(64) NOT NULL,
            user_agent VARCHAR(255) NOT NULL,
            ip_address VARCHAR(45) NOT NULL,
            created_at DATETIME NOT NULL,
            last_used_at DATETIME NOT NULL,
            expires_at DATETIME NOT NULL,
            revoked_at DATETIME NULL,
            PRIMARY KEY (id),
            UNIQUE (token_hash),
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE""", "user_id"},
        {"refresh_tokens", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            user_id INTEGER NOT NULL,
            token_hash VARCHAR(64) NOT NULL,
            family_id VARCHAR(32) NOT NULL,
            remember_me BOOL NOT NULL,
            user_agent VARCHAR(255) NOT NULL,
            ip_address VARCHAR(45) NOT NULL,
            created_at DATETIME NOT NULL,
            last_used_at DATETIME NOT NULL,
            expires_at DATETIME NOT NULL,
            revoked_at DATETIME NULL,
            replaced_by_id INTEGER NULL,
            PRIMARY KEY (id),
            UNIQUE (token_hash),
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE""", "user_id,family_id"},
        {"pending_registrations", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            username VARCHAR(30) NOT NULL,
            email VARCHAR(254) NOT NULL,
            full_name VARCHAR(100) NOT NULL,
            `role` VARCHAR(10) NOT NULL,
            password_hash VARCHAR(200) NOT NULL,
            code_hash VARCHAR(64) NOT NULL,
            attempts INTEGER NOT NULL,
            sent_at DATETIME NOT NULL,
            codes_sent INTEGER NOT NULL,
            created_at DATETIME NOT NULL,
            expires_at DATETIME NOT NULL,
            PRIMARY KEY (id)""", "username,email"},
        {"email_verification_codes", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            user_id INTEGER NOT NULL,
            email VARCHAR(254) NOT NULL,
            purpose VARCHAR(10) NOT NULL,
            code_hash VARCHAR(64) NOT NULL,
            attempts INTEGER NOT NULL,
            created_at DATETIME NOT NULL,
            expires_at DATETIME NOT NULL,
            used_at DATETIME NULL,
            PRIMARY KEY (id),
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE""", "user_id"},
        {"password_reset_tokens", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            user_id INTEGER NOT NULL,
            token_hash VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL,
            expires_at DATETIME NOT NULL,
            used_at DATETIME NULL,
            ip_address VARCHAR(45) NOT NULL,
            PRIMARY KEY (id),
            UNIQUE (token_hash),
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE""", "user_id"},
        {"auth_events", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            user_id INTEGER NULL,
            username VARCHAR(254) NOT NULL,
            event VARCHAR(40) NOT NULL,
            success BOOL NOT NULL,
            detail VARCHAR(255) NOT NULL,
            ip_address VARCHAR(45) NOT NULL,
            user_agent VARCHAR(255) NOT NULL,
            created_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL""", "user_id,created_at"},
        {"partners", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            name VARCHAR(150) NOT NULL,
            is_active BOOL NOT NULL,
            PRIMARY KEY (id)""", ""},
        {"partner_emails", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            partner_id INTEGER NOT NULL,
            email VARCHAR(200) NOT NULL,
            is_active BOOL NOT NULL,
            problem VARCHAR(255) NULL,
            problem_at DATETIME NULL,
            PRIMARY KEY (id),
            FOREIGN KEY (partner_id) REFERENCES partners (id)""", ""},
        {"email_templates", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            name VARCHAR(100) NOT NULL,
            mail_type VARCHAR(20) NOT NULL,
            description VARCHAR(255) NOT NULL,
            subject VARCHAR(300) NOT NULL,
            body_html TEXT NOT NULL,
            purpose VARCHAR(20) NOT NULL,
            is_default BOOL NOT NULL,
            created_by VARCHAR(30) NULL,
            created_at DATETIME NOT NULL,
            updated_by VARCHAR(30) NULL,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            UNIQUE (name)""", ""},
        {"uploads", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            filename VARCHAR(255) NOT NULL,
            uploaded_at DATETIME NOT NULL,
            uploaded_by VARCHAR(30) NULL,
            total_rows INTEGER NOT NULL,
            valid_rows INTEGER NOT NULL,
            file_content LONGBLOB NULL,
            PRIMARY KEY (id)""", ""},
        {"transactions", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            upload_id INTEGER NOT NULL,
            excel_row INTEGER NOT NULL,
            rrn VARCHAR(30) NOT NULL,
            approval_date DATE NULL,
            approval_datetime_text VARCHAR(40) NULL,
            txn_datetime DATETIME NOT NULL,
            txn_type VARCHAR(80) NOT NULL,
            txn_id VARCHAR(40) NULL,
            nagad_status VARCHAR(40) NOT NULL,
            amount NUMERIC(14, 2) NOT NULL,
            from_account_name VARCHAR(150) NULL,
            to_account_type VARCHAR(40) NULL,
            stan VARCHAR(20) NOT NULL,
            dest_partner_name VARCHAR(150) NOT NULL,
            dest_institution_code VARCHAR(20) NOT NULL,
            mail_type VARCHAR(20) NULL,
            duplicate_of INTEGER NULL,
            duplicate_of_txn INTEGER NULL,
            PRIMARY KEY (id),
            FOREIGN KEY (upload_id) REFERENCES uploads (id)""", ""},
        {"email_dispatches", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            upload_id INTEGER NOT NULL,
            partner_id INTEGER NOT NULL,
            mail_type VARCHAR(20) NOT NULL,
            to_emails TEXT NOT NULL,
            cc_emails TEXT NOT NULL,
            subject VARCHAR(300) NOT NULL,
            body_html TEXT NOT NULL,
            is_edited BOOL NOT NULL,
            template_id INTEGER NULL,
            txn_count INTEGER NOT NULL,
            total_amount NUMERIC(14, 2) NOT NULL,
            status VARCHAR(20) NOT NULL,
            attempts INTEGER NOT NULL,
            error TEXT NULL,
            sent_at DATETIME NULL,
            delivery_issues TEXT NULL,
            submitted_by VARCHAR(30) NULL,
            submitted_at DATETIME NULL,
            sent_by VARCHAR(30) NULL,
            sending_since DATETIME NULL,
            message_id VARCHAR(255) NULL,
            reply_status VARCHAR(20) NOT NULL,
            reply_status_by VARCHAR(30) NULL,
            reply_status_at DATETIME NULL,
            followup_count INTEGER NOT NULL,
            last_followup_at DATETIME NULL,
            remarks TEXT NULL,
            remarks_by VARCHAR(30) NULL,
            remarks_at DATETIME NULL,
            PRIMARY KEY (id),
            CONSTRAINT ux_dispatch_upload_partner_type UNIQUE (upload_id, partner_id, mail_type),
            FOREIGN KEY (upload_id) REFERENCES uploads (id),
            FOREIGN KEY (partner_id) REFERENCES partners (id)""", ""},
        {"email_events", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            dispatch_id INTEGER NOT NULL,
            kind VARCHAR(20) NOT NULL,
            reply_status VARCHAR(20) NULL,
            note TEXT NULL,
            subject VARCHAR(300) NULL,
            body_html TEXT NULL,
            to_emails TEXT NULL,
            cc_emails TEXT NULL,
            ok BOOL NOT NULL,
            error TEXT NULL,
            message_id VARCHAR(255) NULL,
            reply_id INTEGER NULL,
            created_by VARCHAR(30) NOT NULL,
            created_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            FOREIGN KEY (dispatch_id) REFERENCES email_dispatches (id) ON DELETE CASCADE""", "dispatch_id"},
        {"email_replies", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            dispatch_id INTEGER NULL,
            partner_id INTEGER NULL,
            kind VARCHAR(20) NOT NULL,
            message_id VARCHAR(255) NOT NULL,
            from_email VARCHAR(254) NOT NULL,
            from_name VARCHAR(200) NOT NULL,
            to_emails TEXT NOT NULL,
            cc_emails TEXT NOT NULL,
            subject VARCHAR(500) NOT NULL,
            body_html MEDIUMTEXT NOT NULL,
            snippet VARCHAR(300) NOT NULL,
            matched_by VARCHAR(20) NOT NULL,
            received_at DATETIME NOT NULL,
            found_at DATETIME NOT NULL,
            suggested_dispatch_id INTEGER NULL,
            review_reason VARCHAR(255) NULL,
            handled_by VARCHAR(30) NULL,
            handled_at DATETIME NULL,
            in_reply_to VARCHAR(255) NULL,
            PRIMARY KEY (id),
            UNIQUE (message_id),
            FOREIGN KEY (dispatch_id) REFERENCES email_dispatches (id) ON DELETE CASCADE""",
            "dispatch_id,partner_id,received_at"},
        {"reply_answers", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            dispatch_id INTEGER NOT NULL,
            reply_id INTEGER NOT NULL,
            to_emails TEXT NOT NULL,
            cc_emails TEXT NOT NULL,
            subject VARCHAR(300) NOT NULL,
            body_text TEXT NOT NULL,
            status VARCHAR(12) NOT NULL,
            created_by VARCHAR(30) NOT NULL,
            created_at DATETIME NOT NULL,
            submitted_at DATETIME NOT NULL,
            decided_by VARCHAR(30) NULL,
            decided_at DATETIME NULL,
            decision_note VARCHAR(500) NULL,
            sending_since DATETIME NULL,
            sent_at DATETIME NULL,
            message_id VARCHAR(255) NULL,
            error TEXT NULL,
            attempts INTEGER NOT NULL,
            PRIMARY KEY (id),
            FOREIGN KEY (dispatch_id) REFERENCES email_dispatches (id) ON DELETE CASCADE,
            FOREIGN KEY (reply_id) REFERENCES email_replies (id) ON DELETE CASCADE""", "dispatch_id,reply_id"},
        {"email_reply_attachments", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            reply_id INTEGER NOT NULL,
            filename VARCHAR(255) NOT NULL,
            content_type VARCHAR(100) NOT NULL,
            size INTEGER NOT NULL,
            content LONGBLOB NOT NULL,
            PRIMARY KEY (id),
            FOREIGN KEY (reply_id) REFERENCES email_replies (id) ON DELETE CASCADE""", "reply_id"},
        {"email_reply_reads", """
            reply_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            read_at DATETIME NOT NULL,
            PRIMARY KEY (reply_id, user_id),
            FOREIGN KEY (reply_id) REFERENCES email_replies (id) ON DELETE CASCADE,
            FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE""", ""},
        {"inbox_state", """
            mailbox VARCHAR(255) NOT NULL,
            uidvalidity BIGINT NOT NULL,
            last_uid BIGINT NOT NULL,
            read_until VARCHAR(40) NULL,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY (mailbox)""", ""},
        {"app_settings", """
            `key` VARCHAR(50) NOT NULL,
            `value` TEXT NOT NULL,
            updated_by VARCHAR(30) NULL,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY (`key`)""", ""},
        {"inbox_issues", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            mailbox VARCHAR(255) NOT NULL,
            uidvalidity BIGINT NOT NULL,
            uid BIGINT NOT NULL,
            remote_id VARCHAR(255) NULL,
            sender VARCHAR(254) NOT NULL,
            subject VARCHAR(500) NOT NULL,
            error TEXT NOT NULL,
            attempts INTEGER NOT NULL,
            created_at DATETIME NOT NULL,
            resolved_at DATETIME NULL,
            resolved_by VARCHAR(30) NULL,
            PRIMARY KEY (id)""", ""},
        {"template_variables", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            name VARCHAR(50) NOT NULL,
            description VARCHAR(255) NOT NULL,
            source VARCHAR(20) NOT NULL,
            `value` TEXT NOT NULL,
            `column` VARCHAR(40) NULL,
            aggregate VARCHAR(20) NULL,
            created_by VARCHAR(30) NULL,
            created_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            UNIQUE (name)""", ""},
        {"change_requests", """
            id INTEGER NOT NULL AUTO_INCREMENT,
            entity VARCHAR(20) NOT NULL,
            entity_id INTEGER NULL,
            action VARCHAR(20) NOT NULL,
            summary VARCHAR(255) NOT NULL,
            `before` TEXT NULL,
            `after` TEXT NULL,
            status VARCHAR(20) NOT NULL,
            requested_by VARCHAR(30) NOT NULL,
            requested_at DATETIME NOT NULL,
            decided_by VARCHAR(30) NULL,
            decided_at DATETIME NULL,
            note VARCHAR(500) NULL,
            PRIMARY KEY (id)""", "entity,entity_id,status"},
    };

    /** Columns added after a table was first created: (table, column, SQL type + default). */
    private static final String[][] LATER_COLUMNS = {
        {"email_dispatches", "is_edited", "BOOLEAN NOT NULL DEFAULT FALSE"},
        {"uploads", "file_content", "LONGBLOB NULL"},
        {"partner_emails", "problem", "VARCHAR(255) NULL"},
        {"partner_emails", "problem_at", "DATETIME NULL"},
        {"email_dispatches", "delivery_issues", "TEXT NULL"},
        {"email_dispatches", "submitted_by", "VARCHAR(30) NULL"},
        {"email_dispatches", "submitted_at", "DATETIME NULL"},
        {"email_dispatches", "sent_by", "VARCHAR(30) NULL"},
        {"email_dispatches", "sending_since", "DATETIME NULL"},
        {"email_dispatches", "message_id", "VARCHAR(255) NULL"},
        {"email_dispatches", "reply_status", "VARCHAR(20) NOT NULL DEFAULT 'not_received'"},
        {"email_dispatches", "reply_status_by", "VARCHAR(30) NULL"},
        {"email_dispatches", "reply_status_at", "DATETIME NULL"},
        {"email_dispatches", "followup_count", "INT NOT NULL DEFAULT 0"},
        {"email_dispatches", "last_followup_at", "DATETIME NULL"},
        {"email_dispatches", "remarks", "TEXT NULL"},
        {"email_dispatches", "remarks_by", "VARCHAR(30) NULL"},
        {"email_dispatches", "remarks_at", "DATETIME NULL"},
        {"transactions", "mail_type", "VARCHAR(20) NULL"},
        {"email_dispatches", "mail_type", "VARCHAR(20) NOT NULL DEFAULT 'outward'"},  // older emails were outward
        // the "Final_Report_for_DMS_Claim" file format
        {"transactions", "approval_date", "DATE NULL"},
        {"transactions", "approval_datetime_text", "VARCHAR(40) NULL"},
        {"transactions", "txn_id", "VARCHAR(40) NULL"},
        {"transactions", "from_account_name", "VARCHAR(150) NULL"},
        {"transactions", "to_account_type", "VARCHAR(40) NULL"},
        {"users", "email", "VARCHAR(254) NULL"},
        {"users", "full_name", "VARCHAR(100) NOT NULL DEFAULT ''"},
        {"users", "email_verified", "BOOLEAN NOT NULL DEFAULT FALSE"},
        {"users", "email_verified_at", "DATETIME NULL"},
        {"users", "pending_email", "VARCHAR(254) NULL"},
        {"users", "token_version", "INT NOT NULL DEFAULT 0"},
        {"users", "failed_login_count", "INT NOT NULL DEFAULT 0"},
        {"users", "locked_until", "DATETIME NULL"},
        {"users", "last_login_at", "DATETIME NULL"},
        {"users", "password_changed_at", "DATETIME NULL"},
        {"users", "updated_at", "DATETIME NULL"},
        {"users", "role", "VARCHAR(10) NOT NULL DEFAULT 'user'"},  // existing accounts become "user"
        {"email_dispatches", "template_id", "INT NULL"},
        {"email_events", "message_id", "VARCHAR(255) NULL"},
        {"email_events", "reply_id", "INT NULL"},
        {"email_replies", "partner_id", "INT NULL"},
        {"email_replies", "kind", "VARCHAR(20) NOT NULL DEFAULT 'reply'"},
        {"email_replies", "suggested_dispatch_id", "INT NULL"},
        {"email_replies", "review_reason", "VARCHAR(255) NULL"},
        {"email_replies", "handled_by", "VARCHAR(30) NULL"},
        {"email_replies", "handled_at", "DATETIME NULL"},
        {"email_replies", "in_reply_to", "VARCHAR(255) NULL"},
        {"inbox_state", "read_until", "VARCHAR(40) NULL"},
        {"inbox_issues", "remote_id", "VARCHAR(255) NULL"},
        {"transactions", "duplicate_of", "INT NULL"},
        {"transactions", "duplicate_of_txn", "INT NULL"},
        {"uploads", "uploaded_by", "VARCHAR(30) NULL"},
        {"email_templates", "purpose", "VARCHAR(20) NOT NULL DEFAULT 'initial'"},
    };
    /** Columns that may be empty now: (table, column, SQL type). MySQL only. */
    private static final String[][] NULLABLE_COLUMNS = {
        {"email_replies", "dispatch_id", "INT NULL"},  // other partner mail belongs to no email
    };
    /** Columns that are no longer used: (table, column). Removed on startup when present. */
    private static final String[][] REMOVED_COLUMNS = {
        // not in the file format any more (response codes, account numbers) / partners are matched by name
        {"transactions", "response_code"},
        {"transactions", "is_unknown"},
        {"transactions", "source_account"},
        {"transactions", "beneficiary_account"},
        {"uploads", "unknown_rows"},
        {"partners", "institution_code"},
        // replies are no longer forwarded by email: everyone sees them on Partner Replies
        {"email_replies", "forward_status"},
        {"email_replies", "forward_to"},
        {"email_replies", "forward_note"},
        {"email_replies", "forwarded_at"},
    };
    /** Columns that hold longer values now: (table, column, SQL type). MySQL only. */
    private static final String[][] WIDENED_COLUMNS = {
        {"transactions", "txn_type", "VARCHAR(80) NOT NULL"},
        {"transactions", "nagad_status", "VARCHAR(40) NOT NULL"},
    };

    /** On startup: create missing tables, then bring older tables up to date. */
    public void createTables(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            Set<String> existing = tables(c);
            for (String[] table : TABLES) {
                if (existing.contains(table[0])) continue;
                try (Statement st = c.createStatement()) {
                    st.execute(createTableSql(table[0], table[1]));
                    for (String column : table[2].isEmpty() ? new String[0] : table[2].split(",")) {
                        st.execute("CREATE INDEX ix_" + table[0] + "_" + column + " ON " + table[0] + " (" + column + ")");
                    }
                }
            }
            addMissingColumns(c);
        }
    }

    private String createTableSql(String table, String body) {
        String sql = "CREATE TABLE " + table + " (\n" + body + "\n)";
        if (!mysql) {  // H2 and others: the generic names of the MySQL types
            sql = sql.replace("LONGBLOB", "BLOB").replace("MEDIUMTEXT", "CLOB").replace(" TEXT", " CLOB");
        }
        return sql;
    }

    private Set<String> tables(Connection c) throws SQLException {
        Set<String> out = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), c.getSchema(), "%", new String[]{"TABLE"})) {
            while (rs.next()) out.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** column name (lower case) -> {type name, size, nullable} */
    private Map<String, Object[]> columns(Connection c, String table) throws SQLException {
        Map<String, Object[]> out = new LinkedHashMap<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), metaName(md, table), "%")) {
            while (rs.next()) {
                out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), new Object[]{
                        rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"), "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))});
            }
        }
        return out;
    }

    private String metaName(DatabaseMetaData md, String name) throws SQLException {
        return md.storesUpperCaseIdentifiers() ? name.toUpperCase(Locale.ROOT) : name;
    }

    /** index name -> [unique, columns...] */
    private Map<String, List<String>> indexes(Connection c, String table, boolean uniqueOnly) throws SQLException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, Map<Integer, String>> ordered = new HashMap<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getIndexInfo(c.getCatalog(), c.getSchema(), metaName(md, table), uniqueOnly, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                ordered.computeIfAbsent(name, x -> new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
            }
        }
        for (Map.Entry<String, Map<Integer, String>> e : ordered.entrySet()) out.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        return out;
    }

    private void addMissingColumns(Connection c) throws SQLException {
        for (String[] later : LATER_COLUMNS) {
            if (!columns(c, later[0]).containsKey(later[1])) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE " + later[0] + " ADD COLUMN `" + later[1] + "` " + later[2]);
                }
            }
        }
        // users.email must be unique (the index is created separately so this also works on other databases)
        boolean uniqueEmail = indexes(c, "users", true).values().stream().anyMatch(cols -> cols.equals(List.of("email")));
        if (!uniqueEmail) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE UNIQUE INDEX ux_users_email ON users (email)");
            }
        }
        dropRemovedColumns(c);
        upgradeDispatchUniqueKey(c);
        widenColumns(c);
        makeNullable(c);
        try (Statement st = c.createStatement()) {  // replies stored before partner_id existed
            st.execute("UPDATE email_replies SET partner_id = (SELECT partner_id FROM email_dispatches "
                    + "WHERE email_dispatches.id = email_replies.dispatch_id) "
                    + "WHERE partner_id IS NULL AND dispatch_id IS NOT NULL");
        }
    }

    private void makeNullable(Connection c) throws SQLException {
        if (!mysql) return;
        for (String[] n : NULLABLE_COLUMNS) {
            Object[] current = columns(c, n[0]).get(n[1]);
            if (current != null && !(Boolean) current[2]) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE " + n[0] + " MODIFY " + n[1] + " " + n[2]);
                }
            }
        }
    }

    /** Drop REMOVED_COLUMNS one by one; a failure is reported but does not stop the server. */
    private void dropRemovedColumns(Connection c) throws SQLException {
        for (String[] r : REMOVED_COLUMNS) {
            if (!columns(c, r[0]).containsKey(r[1])) continue;
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE " + r[0] + " DROP COLUMN " + r[1]);
                LOG.info("Removed column {}.{} (no longer used).", r[0], r[1]);
            } catch (SQLException e) {
                LOG.warn("Could not remove column {}.{}: {}", r[0], r[1], e.getMessage());
            }
        }
    }

    private void widenColumns(Connection c) throws SQLException {
        if (!mysql) return;
        for (String[] w : WIDENED_COLUMNS) {
            Object[] current = columns(c, w[0]).get(w[1]);
            int wanted = Integer.parseInt(w[2].substring(w[2].indexOf('(') + 1, w[2].indexOf(')')));
            if (current != null && (Integer) current[1] > 0 && (Integer) current[1] < wanted) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE " + w[0] + " MODIFY " + w[1] + " " + w[2]);
                }
            }
        }
    }

    /**
     * One email per partner AND mail type: replace the old unique key (upload_id, partner_id) with
     * (upload_id, partner_id, mail_type). The new key is added first, so the foreign key on upload_id
     * always has an index (MySQL needs one).
     */
    private void upgradeDispatchUniqueKey(Connection c) {
        if (!mysql) return;
        try {
            Map<String, List<String>> keys = indexes(c, "email_dispatches", true);
            List<String> old = new ArrayList<>();
            boolean hasNew = false;
            for (Map.Entry<String, List<String>> e : keys.entrySet()) {
                if (e.getValue().equals(List.of("upload_id", "partner_id"))) old.add(e.getKey());
                if (e.getValue().equals(List.of("upload_id", "partner_id", "mail_type"))) hasNew = true;
            }
            try (Statement st = c.createStatement()) {
                if (!hasNew) {
                    st.execute("ALTER TABLE email_dispatches ADD UNIQUE KEY ux_dispatch_upload_partner_type "
                            + "(upload_id, partner_id, mail_type)");
                }
                for (String name : old) st.execute("ALTER TABLE email_dispatches DROP INDEX `" + name + "`");
            }
        } catch (SQLException e) {
            LOG.warn("Could not update the unique key of email_dispatches ({}). "
                    + "A partner with two mail types in one file may fail to prepare.", e.getMessage());
        }
    }
}
