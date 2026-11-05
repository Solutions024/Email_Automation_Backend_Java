package com.nagad.npsb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All settings are read from the .env file (see .env.example) and the environment.
 *
 * The settings admins can change on the System Settings page (ServerSettings.FIELDS) are kept in a
 * map and read every time they are used, so a change applies at once.
 */
public final class Config {
    private Config() {}

    // (before ENV: it is read while ENV is loaded)
    private static final Pattern LINE = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_.]*)\\s*=\\s*(.*)$");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}:]+)(?::-([^}]*))?}");
    private static final Map<String, String> ENV = loadEnvironment();
    /** Settings that can change while the server runs (ServerSettings). */
    private static final Map<String, Object> LIVE = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- .env

    private static Map<String, String> loadEnvironment() {
        Map<String, String> values = new HashMap<>();
        Path file = findDotEnv();
        if (file != null) {
            try {
                values.putAll(parseDotEnv(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                System.err.println("WARNING: could not read " + file + ": " + e.getMessage());
            }
        }
        values.putAll(System.getenv());  // like load_dotenv(): the environment wins over .env
        return values;
    }

    /** backend/.env: looked for from the working folder upwards, then from the jar's folder upwards. */
    private static Path findDotEnv() {
        List<Path> starts = new ArrayList<>();
        starts.add(Paths.get("").toAbsolutePath());
        try {
            Path jar = Paths.get(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            starts.add(Files.isDirectory(jar) ? jar : jar.getParent());
        } catch (Exception ignored) {
            // no code source: the working folder only
        }
        for (Path start : starts) {
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve(".env");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }


    /** The .env format of python-dotenv: KEY=value, quotes, # comments, ${VAR} expansion. */
    static Map<String, String> parseDotEnv(String text) {
        Map<String, String> out = new HashMap<>();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().isEmpty() || line.strip().startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1), raw = m.group(2);
            String value;
            boolean expand = true;
            if (raw.startsWith("\"") || raw.startsWith("'")) {
                char quote = raw.charAt(0);
                StringBuilder sb = new StringBuilder(raw.substring(1));
                while (sb.indexOf(String.valueOf(quote)) < 0 && i + 1 < lines.length) {  // multi-line value
                    sb.append('\n').append(lines[++i]);
                }
                String body = sb.toString();
                int end = closingQuote(body, quote);
                value = end < 0 ? body : body.substring(0, end);
                if (quote == '"') value = unescape(value);
                else expand = false;
            } else {
                int hash = raw.indexOf(" #");
                value = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            }
            if (expand) value = expand(value, out);
            out.put(key, value);
        }
        return out;
    }

    private static int closingQuote(String s, char quote) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && quote == '"') { i++; continue; }
            if (s.charAt(i) == quote) return i;
        }
        return -1;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String expand(String value, Map<String, String> sofar) {
        Matcher m = VARIABLE.matcher(value);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String found = System.getenv(name);
            if (found == null) found = sofar.get(name);
            if (found == null) found = m.group(2) == null ? "" : m.group(2);
            m.appendReplacement(b, Matcher.quoteReplacement(found));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---------------------------------------------------------------- reading values

    public static String env(String name, String def) {
        String v = ENV.get(name);
        return v == null ? def : v;
    }

    private static int envInt(String name, String def) {
        String v = env(name, def).strip();
        try {
            return Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " in .env must be a whole number, not '" + v + "'");
        }
    }

    private static boolean envBool(String name, String def) {
        return env(name, def).toLowerCase().equals("true");
    }

    private static List<String> envList(String name, String def) {
        List<String> out = new ArrayList<>();
        for (String item : env(name, def).split(",")) if (!item.strip().isEmpty()) out.add(item.strip());
        return out;
    }

    // ---------------------------------------------------------------- fixed settings (need a restart)

    // MySQL (the same details you use to connect in MySQL Workbench)
    public static final String DB_HOST = env("DB_HOST", "localhost");
    public static final int DB_PORT = envInt("DB_PORT", "3306");
    public static final String DB_USER = env("DB_USER", "root");
    public static final String DB_PASSWORD = env("DB_PASSWORD", "");
    public static final String DB_NAME = env("DB_NAME", "npsb_dispute");
    // Optional: a JDBC URL to use a different database (e.g. jdbc:h2:./test-db;MODE=MySQL)
    public static final String DATABASE_URL = env("DATABASE_URL", "");

    // Sender of every email: the Gmail address (MAIL_PROVIDER=gmail) or MAIL_FROM (MAIL_PROVIDER=smtp).
    public static final List<String> CC_EMAILS = envList("CC_EMAILS", "");  // default for new emails

    // Login (JWT). Set JWT_SECRET to a long random string in .env. Without it a random secret is used,
    // so everyone is signed out when the server restarts.
    public static final boolean JWT_SECRET_IS_RANDOM = Py.isEmpty(ENV.get("JWT_SECRET"));
    public static final String JWT_SECRET = JWT_SECRET_IS_RANDOM ? randomToken(48) : ENV.get("JWT_SECRET");
    public static final String JWT_ISSUER = "npsb-email-automation";
    // true when the app is served over HTTPS (the cookie is then only sent over HTTPS)
    public static final boolean COOKIE_SECURE = envBool("COOKIE_SECURE", "false");
    // "Forgot password" emails link to this address (the frontend); also the CORS origin
    public static final String APP_BASE_URL = Py.rstrip(env("APP_BASE_URL", "http://localhost:5173"), "/");

    // Defaults of the Email Configuration page (Settings.DEFAULTS)
    public static final int FOLLOWUP_DUE_DAYS = envInt("FOLLOWUP_DUE_DAYS", "3");
    public static final boolean AUTO_FOLLOWUP_ENABLED = envBool("AUTO_FOLLOWUP_ENABLED", "false");
    public static final int AUTO_FOLLOWUP_MAX = envInt("AUTO_FOLLOWUP_MAX", "3");
    public static final int DUPLICATE_WINDOW_DAYS = envInt("DUPLICATE_WINDOW_DAYS", "30");
    public static final List<String> OPS_ALERT_EMAILS = envList("OPS_ALERT_EMAILS", "");
    public static final boolean FORWARD_REPLIES = envBool("FORWARD_REPLIES", "true");

    public static final int VERIFICATION_MAX_ATTEMPTS = 5;      // wrong codes before it expires
    public static final int VERIFICATION_RESEND_SECONDS = 60;   // wait before sending another

    // Where the API listens (uvicorn: --host / --port)
    public static final String HOST = env("HOST", "127.0.0.1");
    public static final int PORT = envInt("PORT", "8000");

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---------------------------------------------------------------- live settings (System Settings)

    static {
        LIVE.put("MAIL_PROVIDER", env("MAIL_PROVIDER", "gmail").strip().toLowerCase());
        LIVE.put("GMAIL_ADDRESS", env("GMAIL_ADDRESS", "").strip());
        LIVE.put("GMAIL_APP_PASSWORD", env("GMAIL_APP_PASSWORD", ""));
        LIVE.put("GMAIL_FOLDER", env("GMAIL_FOLDER", "INBOX"));
        LIVE.put("SMTP_HOST", env("SMTP_HOST", "localhost"));
        LIVE.put("SMTP_PORT", envInt("SMTP_PORT", "1025"));
        LIVE.put("SMTP_USER", env("SMTP_USER", ""));
        LIVE.put("SMTP_PASSWORD", env("SMTP_PASSWORD", ""));
        LIVE.put("SMTP_USE_TLS", envBool("SMTP_USE_TLS", "false"));
        LIVE.put("SMTP_MAX_ATTEMPTS", envInt("SMTP_MAX_ATTEMPTS", "3"));
        LIVE.put("SMTP_RETRY_SECONDS", envInt("SMTP_RETRY_SECONDS", "2"));
        LIVE.put("MAIL_FROM", env("MAIL_FROM", "npsb-dispute@nagad.test"));
        LIVE.put("INBOX_ENABLED", envBool("INBOX_ENABLED", "true"));
        LIVE.put("IMAP_HOST", env("IMAP_HOST", ""));
        LIVE.put("IMAP_PORT", envInt("IMAP_PORT", "993"));
        LIVE.put("IMAP_USER", env("IMAP_USER", ""));
        LIVE.put("IMAP_PASSWORD", env("IMAP_PASSWORD", ""));
        LIVE.put("IMAP_FOLDER", env("IMAP_FOLDER", "INBOX"));
        LIVE.put("INBOX_CHECK_SECONDS", envInt("INBOX_CHECK_SECONDS", "120"));
        LIVE.put("INBOX_FIRST_SCAN_DAYS", envInt("INBOX_FIRST_SCAN_DAYS", "60"));
        LIVE.put("AUTO_FOLLOWUP_CHECK_SECONDS", envInt("AUTO_FOLLOWUP_CHECK_SECONDS", "300"));
        LIVE.put("MAKER_CHECKER", envBool("MAKER_CHECKER", "true"));
        LIVE.put("ALLOW_REGISTRATION", envBool("ALLOW_REGISTRATION", "true"));
        LIVE.put("ADMIN_SIGNUP_CODE", env("ADMIN_SIGNUP_CODE", "1234"));
        LIVE.put("EMAIL_DNS_CHECK", envBool("EMAIL_DNS_CHECK", "true"));
        LIVE.put("MAX_FAILED_LOGINS", envInt("MAX_FAILED_LOGINS", "5"));
        LIVE.put("LOCKOUT_MINUTES", envInt("LOCKOUT_MINUTES", "15"));
        LIVE.put("LOGIN_RATE_LIMIT_PER_MINUTE", envInt("LOGIN_RATE_LIMIT_PER_MINUTE", "10"));
        LIVE.put("VERIFICATION_CODE_MINUTES", envInt("VERIFICATION_CODE_MINUTES", "15"));
        LIVE.put("PASSWORD_RESET_MINUTES", envInt("PASSWORD_RESET_MINUTES", "30"));
        LIVE.put("ACCESS_TOKEN_MINUTES", envInt("ACCESS_TOKEN_MINUTES", "15"));
        LIVE.put("REFRESH_TOKEN_DAYS", envInt("REFRESH_TOKEN_DAYS", "1"));
        LIVE.put("REFRESH_TOKEN_REMEMBER_DAYS", envInt("REFRESH_TOKEN_REMEMBER_DAYS", "30"));
    }

    /** The current value of a live setting (String, Integer or Boolean). */
    public static Object get(String name) {
        Object v = LIVE.get(name);
        if (v == null && !LIVE.containsKey(name)) throw new IllegalArgumentException("Unknown setting " + name);
        return v;
    }

    public static void set(String name, Object value) {
        if (!LIVE.containsKey(name)) throw new IllegalArgumentException("Unknown setting " + name);
        LIVE.put(name, value);
    }

    public static String str(String name) { return (String) get(name); }

    public static int num(String name) { return (Integer) get(name); }

    public static boolean flag(String name) { return (Boolean) get(name); }

    // shortcuts used all over the code
    // The mail server values in effect: with MAIL_PROVIDER=gmail they come from the Gmail account (GMAIL_*),
    // otherwise from the SMTP_* / IMAP_* settings.
    public static String MAIL_PROVIDER() { return str("MAIL_PROVIDER"); }
    public static String GMAIL_ADDRESS() { return Py.strip(str("GMAIL_ADDRESS")); }
    public static String SMTP_HOST() { return gmail() ? GMAIL_SMTP_HOST : str("SMTP_HOST"); }
    public static int SMTP_PORT() { return gmail() ? GMAIL_SMTP_PORT : num("SMTP_PORT"); }
    public static String SMTP_USER() { return gmail() ? GMAIL_ADDRESS() : str("SMTP_USER"); }
    public static String SMTP_PASSWORD() { return gmail() ? gmailPassword(str("GMAIL_APP_PASSWORD")) : str("SMTP_PASSWORD"); }
    public static boolean SMTP_USE_TLS() { return gmail() || flag("SMTP_USE_TLS"); }
    public static int SMTP_MAX_ATTEMPTS() { return num("SMTP_MAX_ATTEMPTS"); }
    public static int SMTP_RETRY_SECONDS() { return num("SMTP_RETRY_SECONDS"); }
    public static String MAIL_FROM() { return gmail() ? GMAIL_ADDRESS() : str("MAIL_FROM"); }
    public static boolean INBOX_ENABLED() { return flag("INBOX_ENABLED"); }
    public static int IMAP_PORT() { return gmail() ? GMAIL_IMAP_PORT : num("IMAP_PORT"); }
    public static String IMAP_FOLDER() { return gmail() ? Py.or(Py.strip(str("GMAIL_FOLDER")), "INBOX") : str("IMAP_FOLDER"); }
    public static int INBOX_CHECK_SECONDS() { return num("INBOX_CHECK_SECONDS"); }
    public static int INBOX_FIRST_SCAN_DAYS() { return num("INBOX_FIRST_SCAN_DAYS"); }
    public static int AUTO_FOLLOWUP_CHECK_SECONDS() { return num("AUTO_FOLLOWUP_CHECK_SECONDS"); }
    public static boolean MAKER_CHECKER() { return flag("MAKER_CHECKER"); }
    public static boolean ALLOW_REGISTRATION() { return flag("ALLOW_REGISTRATION"); }
    public static String ADMIN_SIGNUP_CODE() { return str("ADMIN_SIGNUP_CODE"); }
    public static boolean EMAIL_DNS_CHECK() { return flag("EMAIL_DNS_CHECK"); }
    public static int MAX_FAILED_LOGINS() { return num("MAX_FAILED_LOGINS"); }
    public static int LOCKOUT_MINUTES() { return num("LOCKOUT_MINUTES"); }
    public static int LOGIN_RATE_LIMIT_PER_MINUTE() { return num("LOGIN_RATE_LIMIT_PER_MINUTE"); }
    public static int VERIFICATION_CODE_MINUTES() { return num("VERIFICATION_CODE_MINUTES"); }
    public static int PASSWORD_RESET_MINUTES() { return num("PASSWORD_RESET_MINUTES"); }
    public static int ACCESS_TOKEN_MINUTES() { return num("ACCESS_TOKEN_MINUTES"); }
    public static int REFRESH_TOKEN_DAYS() { return num("REFRESH_TOKEN_DAYS"); }
    public static int REFRESH_TOKEN_REMEMBER_DAYS() { return num("REFRESH_TOKEN_REMEMBER_DAYS"); }

    // ---------------------------------------------------------------- derived values

    public static final String GMAIL_SMTP_HOST = "smtp.gmail.com", GMAIL_IMAP_HOST = "imap.gmail.com";
    public static final int GMAIL_SMTP_PORT = 587, GMAIL_IMAP_PORT = 993;

    /** Google shows app passwords in groups of four ("abcd efgh ijkl mnop"): the spaces are not part of it. */
    public static String gmailPassword(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    public static boolean gmail() { return "gmail".equals(MAIL_PROVIDER()); }

    /** The IMAP server in effect (filled in for Gmail). */
    public static String imapHost() {
        if (gmail()) return GMAIL_IMAP_HOST;
        if (!str("IMAP_HOST").isEmpty()) return str("IMAP_HOST");
        return Py.strip(SMTP_HOST()).toLowerCase().equals(GMAIL_SMTP_HOST) ? GMAIL_IMAP_HOST : "";
    }

    public static String imapUser() { return gmail() ? GMAIL_ADDRESS() : Py.or(str("IMAP_USER"), SMTP_USER()); }

    public static String imapPassword() { return gmail() ? SMTP_PASSWORD() : Py.or(str("IMAP_PASSWORD"), SMTP_PASSWORD()); }

    /** The address every email is sent from (and partners' replies come back to). */
    public static String mailFrom() { return MAIL_FROM(); }

    /** The mailbox partners' replies are read from. */
    public static String mailboxAddress() { return imapUser(); }
}
