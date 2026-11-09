package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.config.NpsbProperties;
import com.nagad.npsb.entity.AppSetting;
import com.nagad.npsb.repository.AppSettingRepository;
import com.nagad.npsb.util.Crypto;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.util.PyJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server settings that admins change on the System Settings page instead of in backend/.env.
 *
 * Each setting starts with its .env value. Once an admin saves it, it is stored in app_settings
 * (key "server.NAME") and applied to the running server at once: the code reads these values from
 * LiveSettings every time it uses them. Passwords and the admin code are stored encrypted with a key made
 * from JWT_SECRET (if JWT_SECRET changes, they can no longer be read and the .env value is used).
 *
 * Not here, because they can't change while the server runs: the database connection, JWT_SECRET,
 * APP_BASE_URL and COOKIE_SECURE.
 */
@Service
public class ServerSettings {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

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

    /**
     * What the System Settings page shows: (id, title, description, shown only for this MAIL_PROVIDER or null,
     * field names). Everything else (email system, reading partner mail, ports, retries, background jobs, sign-up
     * and security) keeps its .env value.
     */
    public static final String[][] PAGE = {
        {"gmail", "Email account",
            "Emails are sent from this Gmail account and partners' replies are read from it. Use an app password "
            + "(Google Account > Security > App passwords), not the normal password.", "gmail",
            "GMAIL_ADDRESS,GMAIL_APP_PASSWORD,GMAIL_FROM"},
        {"smtp", "Email account",
            "Emails are sent through this mail server and partners' replies are read from the same mailbox.", "smtp",
            "SMTP_HOST,SMTP_USER,SMTP_PASSWORD,MAIL_FROM"},
        {"approvals", "Approvals (maker-checker)",
            "Users' changes to email templates and template variables wait until an admin approves them. Admins' own changes and partner changes apply at once.", null,
            "MAKER_CHECKER"},
    };
    private static final String[][] PROVIDERS = {
        {"gmail", "Gmail / Google Workspace"}, {"smtp", "Other mail server (SMTP and IMAP)"}};

    public static final List<Field> FIELDS = List.of(
        new Field("MAIL_PROVIDER", "Email system", "choice", "provider",
            "Gmail. Changing it applies at once to sending and to reading replies.", null, null, false, PROVIDERS),
        new Field("GMAIL_ADDRESS", "Gmail account (signs in)", "email", "gmail",
            "The account that signs in to send emails and read partners' replies, e.g. npsb.dispute@gmail.com"),
        new Field("GMAIL_APP_PASSWORD", "App password", "secret", "gmail",
            "The 16-character app password Google shows once (spaces don't matter), not the account's normal password"),
        new Field("GMAIL_FROM", "Sender address", "email", "gmail",
            "Shown as From; partners reply to it. Empty: the Gmail account. Another address must be added in Gmail under "
            + "Settings > Accounts > Send mail as, and its replies must reach this Gmail inbox"),
        new Field("GMAIL_FOLDER", "Folder (label)", "text", "gmail", "Where partners' replies arrive. Usually INBOX", null, null, true, new String[0][]),
        new Field("SMTP_HOST", "Server", "host", "smtp", "e.g. mail.your-company.com", null, null, true, new String[0][]),
        new Field("SMTP_PORT", "Port", "int", "smtp", "587 with STARTTLS", 1, 65535),
        new Field("SMTP_USE_TLS", "Use STARTTLS encryption", "bool", "smtp", "On for most real servers"),
        new Field("SMTP_USER", "Sign-in account", "user", "smtp", "The mailbox that signs in to the server. Empty: no sign-in (test mail server)"),
        new Field("SMTP_PASSWORD", "Password", "secret", "smtp", "The mailbox's password (or app password)"),
        new Field("MAIL_FROM", "Sender address", "email", "smtp",
            "Shown as From; partners reply to it. Can differ from the sign-in account if the server allows sending as it",
            null, null, true, new String[0][]),
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
    private final Map<String, Object> envValues;
    /** Saved settings that could not be read at startup (the .env value is used). */
    private final Set<String> unreadable = Collections.synchronizedSet(new HashSet<>());

    public static final class SettingsError extends RuntimeException {
        public SettingsError(String message) { super(message); }
    }

    private final LiveSettings live;
    private final NpsbProperties properties;
    private final AppSettingRepository appSettings;
    private final TransactionTemplate transactionTemplate;

    public ServerSettings(LiveSettings live, NpsbProperties properties, AppSettingRepository appSettings,
                          TransactionTemplate transactionTemplate) {
        this.live = live;
        this.properties = properties;
        this.appSettings = appSettings;
        this.transactionTemplate = transactionTemplate;
        Map<String, Object> env = new LinkedHashMap<>();
        for (Field f : FIELDS) env.put(f.name(), live.get(f.name()));  // before load() applies the saved values
        envValues = Collections.unmodifiableMap(env);
    }

    /** The .env values, kept before anything is changed (ServerSettings.ENV_VALUES in the old code). */
    public Map<String, Object> envValues() {
        return envValues;
    }

    // ---------------------------------------------------------------- encryption of secrets

    private byte[] key() {
        return Crypto.sha256(("npsb-server-settings:" + properties.effectiveJwtSecret()).getBytes(StandardCharsets.UTF_8));
    }

    private String encrypt(String value) {
        return "enc:" + Crypto.fernetEncrypt(key(), value.getBytes(StandardCharsets.UTF_8));
    }

    private String decrypt(String stored) throws Crypto.InvalidToken {
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

    /** The saved System Settings rows (key LIKE 'server.%'), by setting name. */
    private Map<String, AppSetting> rows() {
        Map<String, AppSetting> out = new LinkedHashMap<>();
        for (AppSetting r : appSettings.findAll()) {
            if (!r.getKey().startsWith(PREFIX)) continue;
            String name = r.getKey().substring(PREFIX.length());
            if (BY_NAME.containsKey(name)) out.put(name, r);
        }
        return out;
    }

    /** At startup: apply every saved setting to the running server. */
    public void load() {
        unreadable.clear();
        dropObsolete();
        for (Map.Entry<String, AppSetting> e : rows().entrySet()) {
            String name = e.getKey();
            Field field = BY_NAME.get(name);
            Object value;
            try {
                Object stored = PyJson.loads(e.getValue().getValue());
                value = field.kind().equals("secret") ? decrypt((String) stored) : clean(field, stored);
            } catch (Crypto.InvalidToken | RuntimeException ex) {
                unreadable.add(name);
                live.set(name, envValues.get(name));
                LOG.warn("System Settings: the saved " + name + " can't be read; using the .env value");
                continue;
            }
            live.set(name, value);
        }
    }

    /** Remove saved settings of the old Microsoft 365 provider (and a saved MAIL_PROVIDER=microsoft). */
    private void dropObsolete() {
        List<String> keys = new ArrayList<>();
        for (String name : OBSOLETE) keys.add(PREFIX + name);
        boolean dropped = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean any = false;
            for (AppSetting r : appSettings.findAllById(keys)) {
                appSettings.delete(r);
                any = true;
            }
            AppSetting provider = appSettings.findById(PREFIX + "MAIL_PROVIDER").orElse(null);
            if (provider != null && "\"microsoft\"".equals(Py.strip(provider.getValue()))) {
                appSettings.delete(provider);
                any = true;
            }
            return any;
        }));
        if (dropped) {
            LOG.info("System Settings: removed the saved Microsoft 365 settings (the app uses Gmail now)");
        }
    }

    /** The values in effect, with the given changes / resets applied (nothing is saved). */
    public Map<String, Object> effective(Map<String, Object> changes, Collection<String> resets) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field f : FIELDS) values.put(f.name(), live.get(f.name()));
        if (resets != null) for (String name : resets) values.put(name, envValues.get(name));
        if (changes != null) values.putAll(changes);
        return values;
    }

    public Map<String, Object> effective() {
        return effective(null, null);
    }

    /** The mail server emails would be sent through with these values (Gmail: smtp.gmail.com with the app password). */
    public static Map<String, Object> smtpValues(Map<String, Object> v) {
        if ("gmail".equals(v.get("MAIL_PROVIDER"))) {
            String address = Py.strip((String) v.get("GMAIL_ADDRESS"));
            return Py.map("host", LiveSettings.GMAIL_SMTP_HOST, "port", LiveSettings.GMAIL_SMTP_PORT, "use_tls", true, "user", address,
                    "password", LiveSettings.gmailPassword((String) v.get("GMAIL_APP_PASSWORD")));
        }
        return Py.map("host", v.get("SMTP_HOST"), "port", v.get("SMTP_PORT"), "use_tls", v.get("SMTP_USE_TLS"),
                "user", v.get("SMTP_USER"), "password", v.get("SMTP_PASSWORD"));
    }

    /** The mailbox that would be read with these values (empty fields: the sending mailbox). */
    public static Map<String, Object> imapValues(Map<String, Object> v) {
        if ("gmail".equals(v.get("MAIL_PROVIDER"))) {
            String address = Py.strip((String) v.get("GMAIL_ADDRESS"));
            String folder = Py.strip((String) v.get("GMAIL_FOLDER"));
            return Py.map("host", LiveSettings.GMAIL_IMAP_HOST, "port", LiveSettings.GMAIL_IMAP_PORT, "user", address,
                    "password", LiveSettings.gmailPassword((String) v.get("GMAIL_APP_PASSWORD")), "folder", folder.isEmpty() ? "INBOX" : folder);
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

    /** Store and apply (committed when it returns). Returns the names that actually changed. */
    @Transactional
    public List<String> save(Map<String, Object> changes, Set<String> resets, String username) {
        Map<String, AppSetting> rows = rows();
        var now = Py.now();
        List<String> changed = new ArrayList<>();
        for (String name : resets) {
            if (rows.containsKey(name)) {
                appSettings.delete(rows.get(name));
                changed.add(name);
            }
            live.set(name, envValues.get(name));
            unreadable.remove(name);
        }
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String name = e.getKey();
            Object value = e.getValue();
            Field field = BY_NAME.get(name);
            if (java.util.Objects.equals(live.get(name), value) && rows.containsKey(name) && !unreadable.contains(name)) continue;
            String stored = PyJson.dumps(field.kind().equals("secret") ? encrypt((String) value) : value);
            AppSetting row = rows.get(name);
            boolean added = row == null;
            if (added) {
                row = new AppSetting();
                row.setKey(PREFIX + name);
            }
            row.setValue(stored);
            row.setUpdatedBy(username);
            row.setUpdatedAt(now);
            if (added) appSettings.save(row);  // inserted at commit, with these values
            live.set(name, value);
            unreadable.remove(name);
            changed.add(name);
        }
        return changed;
    }

    /** Everything the System Settings page shows. Secrets are never sent, only whether they are set. */
    public Map<String, Object> toDict(Map<String, String> names) {
        Map<String, AppSetting> rows = rows();
        List<Object> out = new ArrayList<>();
        for (String[] g : PAGE) {
            List<Object> fields = new ArrayList<>();
            for (String name : g[4].split(",")) {
                Field f = BY_NAME.get(name);
                AppSetting row = rows.get(f.name());
                String source = row != null && !unreadable.contains(f.name()) ? "portal" : "env";
                List<Object> options = new ArrayList<>();
                for (String[] o : f.options()) options.add(Py.map("value", o[0], "label", o[1]));
                Map<String, Object> item = Py.map("name", f.name(), "label", f.label(), "kind", f.kind(), "help", f.help(),
                        "low", f.low(), "high", f.high(), "required", f.required(), "source", source,
                        "unreadable", unreadable.contains(f.name()), "options", options,
                        "updated_by_name", row != null ? names.getOrDefault(row.getUpdatedBy(), row.getUpdatedBy()) : null,
                        "updated_at", row != null ? Py.iso(row.getUpdatedAt()) : null);
                Object current = live.get(f.name()), env = envValues.get(f.name());
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
        return Py.map("groups", out, "mail_provider", live.get("MAIL_PROVIDER"), "secrets_survive_restart", !properties.jwtSecretIsRandom());
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0;
        return true;
    }

    public List<String> usernames() {
        List<String> out = new ArrayList<>();
        for (AppSetting r : rows().values()) if (r.getUpdatedBy() != null && !r.getUpdatedBy().isEmpty()) out.add(r.getUpdatedBy());
        return out;
    }
}
