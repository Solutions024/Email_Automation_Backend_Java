package com.nagad.npsb.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The database connection pool. Before anything uses it, the MySQL database is created if it does not exist yet
 * (nobody has to create it in Workbench) and the tables are created / brought up to date (SchemaInitializer).
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(NpsbProperties properties) throws SQLException {
        NpsbProperties.Db db = properties.getDb();
        HikariConfig hc = new HikariConfig();
        boolean mysql;
        if (!db.getUrl().isEmpty()) {
            String[] parts = fromDatabaseUrl(db.getUrl());
            hc.setJdbcUrl(parts[0]);
            if (parts[1] != null) hc.setUsername(parts[1]);
            if (parts[2] != null) hc.setPassword(parts[2]);
            mysql = parts[0].startsWith("jdbc:mysql:") || parts[0].startsWith("jdbc:mariadb:");
        } else {
            createDatabaseIfMissing(db);
            hc.setJdbcUrl(mysqlUrl(db.getHost(), db.getPort(), db.getName()));
            hc.setUsername(db.getUser());
            hc.setPassword(db.getPassword());
            mysql = true;
        }
        hc.setMaximumPoolSize(15);
        hc.setPoolName("npsb");
        hc.setAutoCommit(false);
        HikariDataSource dataSource = new HikariDataSource(hc);
        try {
            new SchemaInitializer(mysql).createTables(dataSource);
        } catch (SQLException | RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return dataSource;
    }

    static String mysqlUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + (database == null ? "" : database)
                + "?characterEncoding=UTF-8&allowPublicKeyRetrieval=true";
    }

    /** DATABASE_URL: a JDBC URL, or a SQLAlchemy-style mysql URL (mysql+pymysql://user:pass@host:port/db). */
    static String[] fromDatabaseUrl(String url) {
        if (url.startsWith("jdbc:")) return new String[]{url, null, null};
        Matcher m = Pattern.compile("^mysql(?:\\+\\w+)?://(?:([^:@/]*)(?::([^@/]*))?@)?([^:/]+)(?::(\\d+))?/([^?]+).*$").matcher(url);
        if (m.matches()) {
            String user = m.group(1) == null ? null : URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            String password = m.group(2) == null ? null : URLDecoder.decode(m.group(2), StandardCharsets.UTF_8);
            int port = m.group(4) == null ? 3306 : Integer.parseInt(m.group(4));
            return new String[]{mysqlUrl(m.group(3), port, m.group(5)), user, password};
        }
        throw new IllegalStateException("DATABASE_URL must be a JDBC URL (e.g. jdbc:mysql://localhost:3306/npsb_dispute or "
                + "jdbc:h2:./test-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE), not " + url);
    }

    /** Create the MySQL database (schema) if it does not exist yet. */
    static void createDatabaseIfMissing(NpsbProperties.Db db) throws SQLException {
        try (Connection c = DriverManager.getConnection(mysqlUrl(db.getHost(), db.getPort(), null), db.getUser(), db.getPassword());
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + db.getName() + "` CHARACTER SET utf8mb4");
        }
    }
}
