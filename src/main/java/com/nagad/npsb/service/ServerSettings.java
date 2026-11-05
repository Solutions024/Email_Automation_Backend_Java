package com.nagad.npsb.service;

import com.nagad.npsb.Config;
import com.nagad.npsb.Crypto;
import com.nagad.npsb.Py;
import com.nagad.npsb.PyJson;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.AppSetting;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Server settings that admins change on the System Settings page instead of in backend/.env.
 *
 * Each setting starts with its .env value. Once an admin saves it, it is stored in app_settings
 * (key "server.NAME") and applied to the running server at once: the code reads these values from
 * Config every time it uses them. Passwords and the admin code are stored encrypted with a key made
 * from JWT_SECRET (if JWT_SECRET changes, they can no longer be read and the .env value is used).
 *
 * Not here, because they can't change while the server runs: the database connection, JWT_SECRET,
 * APP_BASE_URL and COOKIE_SECURE.
 */
public final class ServerSettings {
    private ServerSettings() {}

    private static final Logger LOG = Logger.getLogger("npsb");

    public static final String PREFIX = "server.";
    private static final Pattern HOST_RE = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$", Pattern.UNICODE_CHARACTER_CLASS);

    public record Field(String name, String label, String kind, String group, String help, Integer low, Integer high,
                        boolean required, String[][] options) {
        Field(String name, String label, String kind, String group, String help) {
            this(name, label, kind, group, help, null, null, false, new String[0][]);
        }

        Field(String name, String label, String kind, String group, String help, int low, int high) {
            this(name, label, kind, group, help, low, high, false, new String[0][]);
        }
    }

    /** (id, title, description, shown only for this MAIL_PROVIDER or null) */
    public static final String[][] GROUPS = {
        {"provider", "Email system", "How emails are sent and partners' replies are read.", null},
        {"gmail", "Gmail account",
            "Emails, follow-ups and sign-in codes are sent from this Gmail (or Google Workspace) account through smtp.gmail.com, "
            + "and partners' replies are read from it through imap.gmail.com. The account needs 2-Step Verification and an "
            + "app password (Google Account > Security > App passwords); IMAP must be on in Gmail's settings.", "gmail"},
        {"smtp", "Mail server (sending)", "Every email, follow-up and sign-in code is sent through this server.", "smtp"},
        {"inbox", "Mailbox (IMAP)",
            "The mailbox partners' replies and other mail are read from. Empty fields use the sending mailbox.", "smtp"},
        {"sending", "Sending retries", "What happens when the mail server can't be reached.", null},
        {"reading", "Reading partner mail", "", null},
        {"jobs", "Background jobs", "", null},
        {"approvals", "Approvals (maker-checker)",
            "Changes to partners, email templates and template variables wait until an admin who didn't make them approves.", null},
        {"security", "Sign-up and security", "", null},
    };
    private static final String[][] PROVIDERS = {
        {"gmail", "Gmail / Google Workspace"}, {"smtp", "Other mail server (SMTP and IMAP)"}};

    public static final List<Field> FIELDS = List.of(
        new Field("MAIL_PROVIDER", "Email system", "choice", "provider",
            "Gmail. Changing it applies at once to sending and to reading replies.", null, null, false, PROVIDERS),
        new Field("GMAIL_ADDRESS", "Gmail address", "email", "gmail",
            "The account emails are sent from and partners' replies are read from, e.g. npsb.dispute@gmail.com"),
        new Field("GMAIL_APP_PASSWORD", "App password", "secret", "gmail",
            "The 16-character app password Google shows once (spaces don't matter), not the account's normal password"),
        new Field("GMAIL_FOLDER", "Folder (label)", "text", "gmail", "Where partners' replies arrive. Usually INBOX", null, null, true, new String[0][]),
        new Field("SMTP_HOST", "Server", "host", "smtp", "e.g. mail.your-company.com", null, null, true, new String[0][]),
        new Field("SMTP_PORT", "Port", "int", "smtp", "587 with STARTTLS", 1, 65535),
        new Field("SMTP_USE_TLS", "Use STARTTLS encryption", "bool", "smtp", "On for most real servers"),
        new Field("SMTP_USER", "User", "user", "smtp", "The mailbox that signs in. Empty: no sign-in (test mail server)"),
        new Field("SMTP_PASSWORD", "Password", "secret", "smtp", "The mailbox's password (or app password)"),
        new Field("MAIL_FROM", "Sender address", "email", "smtp",
            "Shown as From; partners' replies come back here. Keep it the same as the user", null, null, true, new String[0][]),
        new Field("SMTP_MAX_ATTEMPTS", "Attempts per email", "int", "sending", "Before the email is marked FAILED", 1, 10),
        new Field("SMTP_RETRY_SECONDS", "Wait between attempts (seconds)", "int", "sending", "", 0, 300),
        new Field("INBOX_ENABLED", "Read partner mail", "bool", "reading", "Off: the mailbox is not checked"),
        new Field("IMAP_HOST", "IMAP server", "host", "inbox", "e.g. mail.your-company.com"),
        new Field("IMAP_PORT", "Port", "int", "inbox", "993 (SSL)", 1, 65535),
        new Field("IMAP_USER", "User", "user", "inbox", "Empty: the sending user"),
        new Field("IMAP_PASSWORD", "Password", "secret", "inbox", "Empty: the sending password"),
        new Field("IMAP_FOLDER", "Folder", "text", "inbox", "Usually INBOX", null, null, true, new String[0][]),
        new Field("INBOX_CHECK_SECONDS", "Check every (seconds)", "int", "reading", "", 30, 86400),
        new Field("INBOX_FIRST_SCAN_DAYS", "First check looks back (days)", "int", "reading", "", 1, 365),
        new Field("AUTO_FOLLOWUP_CHECK_SECONDS", "Look for due follow-ups every (seconds)", "int", "jobs",
            "Automatic follow-ups themselves are switched on in Email Configuration", 30, 86400),
        new Field("MAKER_CHECKER", "Changes need a second person's approval", "bool", "approvals",
            "Off: changes apply at once (they are still kept in the change history)"),
        new Field("ALLOW_REGISTRATION", "Anyone can create an account", "bool", "security",
            "Off: only admins add accounts (the first account is always allowed)"),
        new Field("ADMIN_SIGNUP_CODE", "Admin code", "secret", "security", "Needed to create an admin account", 4, 100),
        new Field("EMAIL_DNS_CHECK", "Check that email domains accept mail", "bool", "security", "For accounts and partner addresses"),
        new Field("MAX_FAILED_LOGINS", "Wrong passwords before the account locks", "int", "security", "", 1, 50),
        new Field("LOCKOUT_MINUTES", "Lock for (minutes)", "int", "security", "", 1, 1440),
        new Field("LOGIN_RATE_LIMIT_PER_MINUTE", "Sign-in attempts per minute from one address", "int", "security", "", 1, 1000),
        new Field("VERIFICATION_CODE_MINUTES", "Emailed code valid for (minutes)", "int", "security", "", 1, 120),
        new Field("PASSWORD_RESET_MINUTES", "Password reset link valid for (minutes)", "int", "security", "", 5, 1440),
        new Field("ACCESS_TOKEN_MINUTES", "Session token lifetime (minutes)", "int", "security",
            "Renewed silently while the app is open", 1, 120),
        new Field("REFRESH_TOKEN_DAYS", "Stay signed in for (days)", "int", "security", "Without \"Keep me signed in\"", 1, 30),
        new Field("REFRESH_TOKEN_REMEMBER_DAYS", "\"Keep me signed in\" lasts (days)", "int", "security", "", 1, 365)
    );
    public static final Map<String, Field> BY_NAME = new LinkedHashMap<>();
    static {
        for (Field f : FIELDS) BY_NAME.put(f.name(), f);
    }
    public static final Set<String> SMTP_FIELDS = Set.of("SMTP_HOST", "SMTP_PORT", "SMTP_USE_TLS", "SMTP_USER", "SMTP_PASSWORD", "MAIL_FROM");
    public static final Set<String> IMAP_FIELDS = Set.of("INBOX_ENABLED", "IMAP_HOST", "IMAP_PORT", "IMAP_USER", "IMAP_PASSWORD", "IMAP_FOLDER");
    public static final Set<String> GMAIL_FIELDS = Set.of("GMAIL_ADDRESS", "GMAIL_APP_PASSWORD", "GMAIL_FOLDER");
    /** Settings of the Microsoft 365 provider the app had before Gmail: saved rows are removed at startup. */
    private static final Set<String> OBSOLETE = Set.of("MS_TENANT_ID", "MS_CLIENT_ID", "MS_CLIENT_SECRET", "MS_MAILBOX", "MS_FOLDER");

    /** The .env values, kept before anything is changed. */
    public static final Map<String, Object> ENV_VALUES = new LinkedHashMap<>();
    static {
        for (Field f : FIELDS) ENV_VALUES.put(f.name(), Config.get(f.name()));
    }
    private static final Set<String> UNREADABLE = java.util.Collections.synchronizedSet(new HashSet<>());

    public static final class SettingsError extends RuntimeException {
        public SettingsError(String message) { super(message); }
    }

    // ---------------------------------------------------------------- encryption of secrets

    private static byte[] key() {
        return Crypto.sha256(("npsb-server-settings:" + Config.JWT_SECRET).getBytes(StandardCharsets.UTF_8));
    }

    private static String encrypt(String value) {
        return "enc:" + Crypto.fernetEncrypt(key(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decrypt(String stored) throws Crypto.InvalidToken {
        if (!stored.startsWith("enc:")) throw new Crypto.InvalidToken();
        return new String(Crypto.fernetDecrypt(key(), stored.substring(4)), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- reading and checking values

    /** The value as stored, or SettingsError with a readable message. */
    public static Object clean(Field field, Object value) {
        String label = field.label();
        switch (field.kind()) {
            case "bool" -> {
                if (!(value instanceof Boolean)) throw new SettingsError(label + ": choose on or off.");
                return value;
            }
            case "choice" -> {
                List<String> allowed = new ArrayList<>();
                for (String[] o : field.options()) allowed.add(o[0]);
                if (!(value instanceof String) || !allowed.contains(value)) {
                    throw new SettingsError(label + ": choose one of " + String.join(", ", allowed) + ".");
                }
                return value;
            }
            case "int" -> {
                if (value instanceof Boolean || !(value instanceof Integer || value instanceof Long || value instanceof String)) {
                    throw new SettingsError(label + ": enter a whole number.");
                }
                int number;
                try {
                    String text = Py.strip(String.valueOf(value)).replace("_", "");
                    number = Integer.parseInt(text.startsWith("+") ? text.substring(1) : text);
                } catch (NumberFormatException e) {
                    throw new SettingsError(label + ": enter a whole number.");
                }
                if ((field.low() != null && number < field.low()) || (field.high() != null && number > field.high())) {
                    throw new SettingsError(label + ": must be between " + field.low() + " and " + field.high() + ".");
                }
                return number;
            }
            default -> { /* text kinds below */ }
        }
        if (!(value instanceof String)) throw new SettingsError(label + ": enter text.");
        String text = Py.strip((String) value);
        if (field.kind().equals("secret")) {
            if (field.low() != null && field.low() > 0 && text.length() < field.low()) {
                throw new SettingsError(label + ": at least " + field.low() + " characters.");
            }
            if (text.length() > 500) throw new SettingsError(label + ": too long.");
            return text;
        }
        if (text.isEmpty()) {
            if (field.required()) throw new SettingsError(label + ": can't be empty.");
            return "";
        }
        if (field.kind().equals("host") && !HOST_RE.matcher(text).matches()) {
            throw new SettingsError(label + ": '" + text + "' is not a server name (e.g. mail.your-company.com).");
        }
        if (field.kind().equals("email") && !EMAIL_RE.matcher(text).matches()) {
            throw new SettingsError(label + ": '" + text + "' is not an email address.");
        }
        if (Set.of("user", "text", "id").contains(field.kind())) {
            boolean hasSpace = text.codePoints().anyMatch(Py::isSpace);
            if (text.length() > 254 || (hasSpace && Set.of("user", "id").contains(field.kind()))) {
                throw new SettingsError(label + ": '" + text + "' is not valid.");
            }
        }
        return text;
    }

    private static Map<String, AppSetting> rows(Db db) {
        Map<String, AppSetting> out = new LinkedHashMap<>();
        for (AppSetting r : db.all(AppSetting.class, "WHERE t.`key` LIKE ?", PREFIX + "%")) {
            String name = r.key.substring(PREFIX.length());
            if (BY_NAME.containsKey(name)) out.put(name, r);
        }
        return out;
    }

    /** At startup: apply every saved setting to the running server. */
    public static void load(Db db) {
        UNREADABLE.clear();
        dropObsolete(db);
        for (Map.Entry<String, AppSetting> e : rows(db).entrySet()) {
            String name = e.getKey();
            Field field = BY_NAME.get(name);
            Object value;
            try {
                Object stored = PyJson.loads(e.getValue().value);
                value = field.kind().equals("secret") ? decrypt((String) stored) : clean(field, stored);
            } catch (Crypto.InvalidToken | RuntimeException ex) {
                UNREADABLE.add(name);
                Config.set(name, ENV_VALUES.get(name));
                LOG.warning("System Settings: the saved " + name + " can't be read; using the .env value");
                continue;
            }
            Config.set(name, value);
        }
    }

    /** Remove saved settings of the old Microsoft 365 provider (and a saved MAIL_PROVIDER=microsoft). */
    private static void dropObsolete(Db db) {
        List<String> keys = new ArrayList<>();
        for (String name : OBSOLETE) keys.add(PREFIX + name);
        boolean dropped = false;
        for (AppSetting r : db.all(AppSetting.class, "WHERE t.`key` IN " + Db.in(keys), keys.toArray())) {
            db.delete(r);
            dropped = true;
        }
        AppSetting provider = db.first(AppSetting.class, "WHERE t.`key` = ?", PREFIX + "MAIL_PROVIDER");
        if (provider != null && "\"microsoft\"".equals(Py.strip(provider.value))) {
            db.delete(provider);
            dropped = true;
        }
        if (dropped) {
            db.commit();
            LOG.info("System Settings: removed the saved Microsoft 365 settings (the app uses Gmail now)");
        }
    }

    /** The values in effect, with the given changes / resets applied (nothing is saved). */
    public static Map<String, Object> effective(Map<String, Object> changes, Collection<String> resets) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field f : FIELDS) values.put(f.name(), Config.get(f.name()));
        if (resets != null) for (String name : resets) values.put(name, ENV_VALUES.get(name));
        if (changes != null) values.putAll(changes);
        return values;
    }

    public static Map<String, Object> effective() {
        return effective(null, null);
    }

    /** The mail server emails would be sent through with these values (Gmail: smtp.gmail.com with the app password). */
    public static Map<String, Object> smtpValues(Map<String, Object> v) {
        if ("gmail".equals(v.get("MAIL_PROVIDER"))) {
            String address = Py.strip((String) v.get("GMAIL_ADDRESS"));
            return Py.map("host", Config.GMAIL_SMTP_HOST, "port", Config.GMAIL_SMTP_PORT, "use_tls", true, "user", address,
                    "password", Config.gmailPassword((String) v.get("GMAIL_APP_PASSWORD")));
        }
        return Py.map("host", v.get("SMTP_HOST"), "port", v.get("SMTP_PORT"), "use_tls", v.get("SMTP_USE_TLS"),
                "user", v.get("SMTP_USER"), "password", v.get("SMTP_PASSWORD"));
    }

    /** The mailbox that would be read with these values (empty fields: the sending mailbox). */
    public static Map<String, Object> imapValues(Map<String, Object> v) {
        if ("gmail".equals(v.get("MAIL_PROVIDER"))) {
            String address = Py.strip((String) v.get("GMAIL_ADDRESS"));
            String folder = Py.strip((String) v.get("GMAIL_FOLDER"));
            return Py.map("host", Config.GMAIL_IMAP_HOST, "port", Config.GMAIL_IMAP_PORT, "user", address,
                    "password", Config.gmailPassword((String) v.get("GMAIL_APP_PASSWORD")), "folder", folder.isEmpty() ? "INBOX" : folder);
        }
        String host = (String) v.get("IMAP_HOST");
        if (host.isEmpty()) host = Py.strip((String) v.get("SMTP_HOST")).toLowerCase().equals("smtp.gmail.com") ? "imap.gmail.com" : "";
        return Py.map("host", host, "port", v.get("IMAP_PORT"), "user", Py.or((String) v.get("IMAP_USER"), (String) v.get("SMTP_USER")),
                "password", Py.or((String) v.get("IMAP_PASSWORD"), (String) v.get("SMTP_PASSWORD")), "folder", v.get("IMAP_FOLDER"));
    }

    /** (changes, resets) from the page; SettingsError for an unknown name or a bad value. */
    public static Object[] parseChanges(Map<String, Object> values, List<String> resets) {
        List<String> unknown = new ArrayList<>();
        for (String k : values.keySet()) if (!BY_NAME.containsKey(k)) unknown.add(k);
        for (String k : resets) if (!BY_NAME.containsKey(k)) unknown.add(k);
        if (!unknown.isEmpty()) throw new SettingsError("Unknown setting: " + String.join(", ", unknown) + ".");
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) changes.put(e.getKey(), clean(BY_NAME.get(e.getKey()), e.getValue()));
        Set<String> resetSet = new LinkedHashSet<>(resets);
        resetSet.removeAll(changes.keySet());
        return new Object[]{changes, resetSet};
    }

    /** Store and apply. Returns the names that actually changed. */
    public static List<String> save(Db db, Map<String, Object> changes, Set<String> resets, String username) {
        Map<String, AppSetting> rows = rows(db);
        var now = Py.now();
        List<String> changed = new ArrayList<>();
        for (String name : resets) {
            if (rows.containsKey(name)) {
                db.delete(rows.get(name));
                changed.add(name);
            }
            Config.set(name, ENV_VALUES.get(name));
            UNREADABLE.remove(name);
        }
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String name = e.getKey();
            Object value = e.getValue();
            Field field = BY_NAME.get(name);
            if (java.util.Objects.equals(Config.get(name), value) && rows.containsKey(name) && !UNREADABLE.contains(name)) continue;
            String stored = PyJson.dumps(field.kind().equals("secret") ? encrypt((String) value) : value);
            AppSetting row = rows.get(name);
            if (row == null) {
                row = new AppSetting();
                row.key = PREFIX + name;
                db.add(row);
            }
            row.value = stored;
            row.updatedBy = username;
            row.updatedAt = now;
            Config.set(name, value);
            UNREADABLE.remove(name);
            changed.add(name);
        }
        db.commit();
        return changed;
    }

    /** Everything the System Settings page shows. Secrets are never sent, only whether they are set. */
    public static Map<String, Object> toDict(Db db, Map<String, String> names) {
        Map<String, AppSetting> rows = rows(db);
        List<Object> out = new ArrayList<>();
        for (String[] g : GROUPS) {
            List<Object> fields = new ArrayList<>();
            for (Field f : FIELDS) {
                if (!f.group().equals(g[0])) continue;
                AppSetting row = rows.get(f.name());
                String source = row != null && !UNREADABLE.contains(f.name()) ? "portal" : "env";
                List<Object> options = new ArrayList<>();
                for (String[] o : f.options()) options.add(Py.map("value", o[0], "label", o[1]));
                Map<String, Object> item = Py.map("name", f.name(), "label", f.label(), "kind", f.kind(), "help", f.help(),
                        "low", f.low(), "high", f.high(), "required", f.required(), "source", source,
                        "unreadable", UNREADABLE.contains(f.name()), "options", options,
                        "updated_by_name", row != null ? names.getOrDefault(row.updatedBy, row.updatedBy) : null,
                        "updated_at", row != null ? Py.iso(row.updatedAt) : null);
                Object current = Config.get(f.name()), env = ENV_VALUES.get(f.name());
                if (f.kind().equals("secret")) {
                    item.put("is_set", truthy(current));
                    item.put("env_is_set", truthy(env));
                } else {
                    item.put("value", current);
                    item.put("env_value", env);
                }
                fields.add(item);
            }
            out.add(Py.map("id", g[0], "title", g[1], "description", g[2], "provider", g[3], "fields", fields));
        }
        return Py.map("groups", out, "secrets_survive_restart", !Config.JWT_SECRET_IS_RANDOM);
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0;
        return true;
    }

    public static List<String> usernames(Db db) {
        List<String> out = new ArrayList<>();
        for (AppSetting r : rows(db).values()) if (r.updatedBy != null && !r.updatedBy.isEmpty()) out.add(r.updatedBy);
        return out;
    }
}
