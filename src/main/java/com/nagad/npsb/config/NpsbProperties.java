package com.nagad.npsb.config;

import com.nagad.npsb.util.Py;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The settings that need a restart, bound from application.yml ("npsb.*"), whose values come from the
 * environment and the .env file (see .env.example). The settings admins can change while the server runs
 * are in {@link LiveSettings}; their startup values are under npsb.live.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "npsb")
public class NpsbProperties {

    // ---------------------------------------------------------------- fixed values

    public static final String JWT_ISSUER = "npsb-email-automation";
    /** The built-in admin, created at startup if missing; it creates every other account. */
    public static final String ADMIN_USERNAME = "admin";
    /** Set once; the admin can change it on My Profile. */
    public static final String ADMIN_PASSWORD = "Admin@1234";
    /** Wrong codes before a verification code expires. */
    public static final int VERIFICATION_MAX_ATTEMPTS = 5;
    /** Wait before another verification code can be sent. */
    public static final int VERIFICATION_RESEND_SECONDS = 60;

    // ---------------------------------------------------------------- bound values

    /** The backend folder (set at startup by DotenvEnvironmentPostProcessor). */
    private String backendDir;
    /** The application log folder, absolute (APP_LOG_DIR; set at startup by DotenvEnvironmentPostProcessor). */
    private String appLogDir;

    private final Db db = new Db();

    /** JWT_SECRET. Empty: a random secret, so everyone is signed out when the server restarts. */
    private String jwtSecret = "";
    /** true when the app is served over HTTPS (the cookies are then only sent over HTTPS). */
    private boolean cookieSecure = false;
    /** The frontend: "Forgot password" emails link here; also the one allowed CORS origin. */
    private String appBaseUrl = "http://localhost:5173";
    /**
     * FRONTEND_DIR: the built frontend (its dist folder), relative to the backend folder or absolute. When it exists,
     * the backend also serves the app on its port: /, /login... (FrontendConfig). Empty or missing: the API only.
     */
    private String frontendDir = "";
    /** CC_EMAILS: comma separated default CC addresses for new emails. */
    private String ccEmails = "";
    /** Defaults of the Email Configuration page. */
    private int followupDueDays = 3;
    private boolean autoFollowupEnabled = false;
    private int autoFollowupMax = 3;
    /** Where the built-in admin's sign-in codes are emailed. Empty: the mailbox partner replies are read from. */
    private String adminEmail = "";

    /** Startup values of the settings that can be changed on the System Settings page. */
    private final Live live = new Live();

    private final String generatedJwtSecret = randomToken(48);

    @Getter
    @Setter
    public static class Db {
        /** MySQL (the same details you use to connect in MySQL Workbench). */
        private String host = "localhost";
        private int port = 3306;
        private String user = "root";
        private String password = "";
        private String name = "npsb_dispute";
        /** DATABASE_URL: a JDBC URL (or mysql+pymysql://user:pass@host:port/db) used instead of the values above. */
        private String url = "";
    }

    /** Startup values of {@link LiveSettings} (same names as in .env). */
    @Getter
    @Setter
    public static class Live {
        private String mailProvider = "gmail";
        private String gmailAddress = "";
        private String gmailAppPassword = "";
        private String gmailFolder = "INBOX";
        private String gmailFrom = "";
        private String smtpHost = "localhost";
        private int smtpPort = 1025;
        private String smtpUser = "";
        private String smtpPassword = "";
        private boolean smtpUseTls = false;
        private int smtpMaxAttempts = 3;
        private int smtpRetrySeconds = 2;
        private String mailFrom = "npsb-dispute@nagad.test";
        private boolean inboxEnabled = true;
        private String imapHost = "";
        private int imapPort = 993;
        private String imapUser = "";
        private String imapPassword = "";
        private String imapFolder = "INBOX";
        private int inboxCheckSeconds = 120;
        private int inboxFirstScanDays = 60;
        private int autoFollowupCheckSeconds = 300;
        private boolean makerChecker = true;
        private boolean allowRegistration = true;
        private String adminSignupCode = "1234";
        private boolean emailDnsCheck = true;
        private int maxFailedLogins = 5;
        private int lockoutMinutes = 15;
        private int loginRateLimitPerMinute = 10;
        private int verificationCodeMinutes = 15;
        private int passwordResetMinutes = 30;
        private int accessTokenMinutes = 15;
        private int refreshTokenDays = 1;
        private int refreshTokenRememberDays = 30;
    }

    // ---------------------------------------------------------------- derived values

    /** True when JWT_SECRET is not set (a random one is used). */
    public boolean jwtSecretIsRandom() {
        return Py.isEmpty(jwtSecret);
    }

    /** The secret the access tokens are signed with. */
    public String effectiveJwtSecret() {
        return jwtSecretIsRandom() ? generatedJwtSecret : jwtSecret;
    }

    /** APP_BASE_URL without a trailing slash. */
    public String baseUrl() {
        return Py.rstrip(appBaseUrl, "/");
    }

    /**
     * The frontend's origin for CORS: APP_BASE_URL without its path (http://localhost:5173/some-path
     * -> http://localhost:5173). A browser's Origin header never has a path.
     */
    public String frontendOrigin() {
        String url = baseUrl();
        int scheme = url.indexOf("://");
        int path = scheme < 0 ? -1 : url.indexOf('/', scheme + 3);
        return path < 0 ? url : url.substring(0, path);
    }

    /** CC_EMAILS as a list (blank entries left out). */
    public List<String> ccEmailList() {
        List<String> out = new ArrayList<>();
        for (String item : (ccEmails == null ? "" : ccEmails).split(",")) if (!item.strip().isEmpty()) out.add(item.strip());
        return out;
    }

    /** ADMIN_EMAIL, trimmed and lower case. */
    public String adminEmailAddress() {
        return adminEmail == null ? "" : adminEmail.strip().toLowerCase();
    }

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
