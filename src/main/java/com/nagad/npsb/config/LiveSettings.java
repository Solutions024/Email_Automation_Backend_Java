package com.nagad.npsb.config;

import com.nagad.npsb.util.Py;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The settings admins can change on the System Settings page while the server runs. They start with the values from
 * the environment / .env (npsb.live.* in application.yml); ServerSettings then applies the values saved in the
 * database. Every read gets the current value, so a change applies at once.
 *
 * Keys are the .env names (MAIL_PROVIDER, SMTP_HOST, ...); values are String, Integer or Boolean.
 */
@Component
public class LiveSettings {
    public static final String GMAIL_SMTP_HOST = "smtp.gmail.com", GMAIL_IMAP_HOST = "imap.gmail.com";
    public static final int GMAIL_SMTP_PORT = 587, GMAIL_IMAP_PORT = 993;

    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public LiveSettings(NpsbProperties properties) {
        NpsbProperties.Live l = properties.getLive();
        values.put("MAIL_PROVIDER", l.getMailProvider().strip().toLowerCase());
        values.put("GMAIL_ADDRESS", l.getGmailAddress().strip());
        values.put("GMAIL_APP_PASSWORD", l.getGmailAppPassword());
        values.put("GMAIL_FOLDER", l.getGmailFolder());
        values.put("GMAIL_FROM", l.getGmailFrom().strip());  // sender address; empty: the Gmail address
        values.put("SMTP_HOST", l.getSmtpHost());
        values.put("SMTP_PORT", l.getSmtpPort());
        values.put("SMTP_USER", l.getSmtpUser());
        values.put("SMTP_PASSWORD", l.getSmtpPassword());
        values.put("SMTP_USE_TLS", l.isSmtpUseTls());
        values.put("SMTP_MAX_ATTEMPTS", l.getSmtpMaxAttempts());
        values.put("SMTP_RETRY_SECONDS", l.getSmtpRetrySeconds());
        values.put("MAIL_FROM", l.getMailFrom());
        values.put("INBOX_ENABLED", l.isInboxEnabled());
        values.put("IMAP_HOST", l.getImapHost());
        values.put("IMAP_PORT", l.getImapPort());
        values.put("IMAP_USER", l.getImapUser());
        values.put("IMAP_PASSWORD", l.getImapPassword());
        values.put("IMAP_FOLDER", l.getImapFolder());
        values.put("INBOX_CHECK_SECONDS", l.getInboxCheckSeconds());
        values.put("INBOX_FIRST_SCAN_DAYS", l.getInboxFirstScanDays());
        values.put("AUTO_FOLLOWUP_CHECK_SECONDS", l.getAutoFollowupCheckSeconds());
        values.put("MAKER_CHECKER", l.isMakerChecker());
        values.put("ALLOW_REGISTRATION", l.isAllowRegistration());
        values.put("ADMIN_SIGNUP_CODE", l.getAdminSignupCode());
        values.put("EMAIL_DNS_CHECK", l.isEmailDnsCheck());
        values.put("MAX_FAILED_LOGINS", l.getMaxFailedLogins());
        values.put("LOCKOUT_MINUTES", l.getLockoutMinutes());
        values.put("LOGIN_RATE_LIMIT_PER_MINUTE", l.getLoginRateLimitPerMinute());
        values.put("VERIFICATION_CODE_MINUTES", l.getVerificationCodeMinutes());
        values.put("PASSWORD_RESET_MINUTES", l.getPasswordResetMinutes());
        values.put("ACCESS_TOKEN_MINUTES", l.getAccessTokenMinutes());
        values.put("REFRESH_TOKEN_DAYS", l.getRefreshTokenDays());
        values.put("REFRESH_TOKEN_REMEMBER_DAYS", l.getRefreshTokenRememberDays());
    }

    // ---------------------------------------------------------------- by name

    /** The current value of a live setting (String, Integer or Boolean). */
    public Object get(String name) {
        Object v = values.get(name);
        if (v == null && !values.containsKey(name)) throw new IllegalArgumentException("Unknown setting " + name);
        return v;
    }

    public void set(String name, Object value) {
        if (!values.containsKey(name)) throw new IllegalArgumentException("Unknown setting " + name);
        values.put(name, value);
    }

    public String str(String name) { return (String) get(name); }

    public int num(String name) { return (Integer) get(name); }

    public boolean flag(String name) { return (Boolean) get(name); }

    // ---------------------------------------------------------------- shortcuts
    // The mail server values in effect: with MAIL_PROVIDER=gmail they come from the Gmail account (GMAIL_*),
    // otherwise from the SMTP_* / IMAP_* settings.

    public String mailProvider() { return str("MAIL_PROVIDER"); }
    public boolean gmail() { return "gmail".equals(mailProvider()); }
    public String gmailAddress() { return Py.strip(str("GMAIL_ADDRESS")); }
    public String smtpHost() { return gmail() ? GMAIL_SMTP_HOST : str("SMTP_HOST"); }
    public int smtpPort() { return gmail() ? GMAIL_SMTP_PORT : num("SMTP_PORT"); }
    public String smtpUser() { return gmail() ? gmailAddress() : str("SMTP_USER"); }
    public String smtpPassword() { return gmail() ? gmailPassword(str("GMAIL_APP_PASSWORD")) : str("SMTP_PASSWORD"); }
    public boolean smtpUseTls() { return gmail() || flag("SMTP_USE_TLS"); }
    public int smtpMaxAttempts() { return num("SMTP_MAX_ATTEMPTS"); }
    public int smtpRetrySeconds() { return num("SMTP_RETRY_SECONDS"); }
    /** The address every email is sent from (and partners' replies come back to). */
    public String mailFrom() { return gmail() ? Py.or(Py.strip(str("GMAIL_FROM")), gmailAddress()) : str("MAIL_FROM"); }
    public boolean inboxEnabled() { return flag("INBOX_ENABLED"); }
    public int imapPort() { return gmail() ? GMAIL_IMAP_PORT : num("IMAP_PORT"); }
    public String imapFolder() { return gmail() ? Py.or(Py.strip(str("GMAIL_FOLDER")), "INBOX") : str("IMAP_FOLDER"); }
    public int inboxCheckSeconds() { return num("INBOX_CHECK_SECONDS"); }
    public int inboxFirstScanDays() { return num("INBOX_FIRST_SCAN_DAYS"); }
    public int autoFollowupCheckSeconds() { return num("AUTO_FOLLOWUP_CHECK_SECONDS"); }
    public boolean makerChecker() { return flag("MAKER_CHECKER"); }
    public boolean allowRegistration() { return flag("ALLOW_REGISTRATION"); }
    public String adminSignupCode() { return str("ADMIN_SIGNUP_CODE"); }
    public boolean emailDnsCheck() { return flag("EMAIL_DNS_CHECK"); }
    public int maxFailedLogins() { return num("MAX_FAILED_LOGINS"); }
    public int lockoutMinutes() { return num("LOCKOUT_MINUTES"); }
    public int loginRateLimitPerMinute() { return num("LOGIN_RATE_LIMIT_PER_MINUTE"); }
    public int verificationCodeMinutes() { return num("VERIFICATION_CODE_MINUTES"); }
    public int passwordResetMinutes() { return num("PASSWORD_RESET_MINUTES"); }
    public int accessTokenMinutes() { return num("ACCESS_TOKEN_MINUTES"); }
    public int refreshTokenDays() { return num("REFRESH_TOKEN_DAYS"); }
    public int refreshTokenRememberDays() { return num("REFRESH_TOKEN_REMEMBER_DAYS"); }

    /** Google shows app passwords in groups of four ("abcd efgh ijkl mnop"): the spaces are not part of it. */
    public static String gmailPassword(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    /** The IMAP server in effect (filled in for Gmail). */
    public String imapHost() {
        if (gmail()) return GMAIL_IMAP_HOST;
        if (!str("IMAP_HOST").isEmpty()) return str("IMAP_HOST");
        return Py.strip(smtpHost()).toLowerCase().equals(GMAIL_SMTP_HOST) ? GMAIL_IMAP_HOST : "";
    }

    public String imapUser() { return gmail() ? gmailAddress() : Py.or(str("IMAP_USER"), smtpUser()); }

    public String imapPassword() { return gmail() ? smtpPassword() : Py.or(str("IMAP_PASSWORD"), smtpPassword()); }

    /** The mailbox partners' replies are read from. */
    public String mailboxAddress() { return imapUser(); }
}
