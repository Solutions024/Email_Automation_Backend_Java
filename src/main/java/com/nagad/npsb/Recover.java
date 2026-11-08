package com.nagad.npsb;

import com.nagad.npsb.api.Auth;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.AppSetting;
import com.nagad.npsb.models.AuthEvent;
import com.nagad.npsb.models.EmailVerificationCode;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.ServerSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Recovery when the mail server is broken and nobody can get a sign-in code. Run on the server:
 *
 *     java -jar npsb-dispute-backend.jar recover mail
 *         put the mail server and inbox settings back to the .env values (undoes changes made on the
 *         System Settings page); restart the backend
 *     java -jar npsb-dispute-backend.jar recover code USERNAME
 *         print a one-time sign-in code for this account (valid for VERIFICATION_CODE_MINUTES); nothing is emailed
 *
 * Sign in with the code, then fix the settings on the System Settings page. Every use is written to the security log.
 */
public final class Recover {
    private Recover() {}

    static final String USAGE = """
            Recovery when the mail server is broken and nobody can get a sign-in code. Run on the server:

                java -jar npsb-dispute-backend.jar recover mail            put the mail server and inbox settings back to the .env values
                                                                           (undoes changes made on the System Settings page); restart the backend
                java -jar npsb-dispute-backend.jar recover code USERNAME   print a one-time sign-in code for this account (valid for
                                                                           VERIFICATION_CODE_MINUTES); nothing is emailed

            Sign in with the code, then fix the settings on the System Settings page. Every use is written to the
            security log.
            """;

    static List<String> mailSettings() {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(ServerSettings.SMTP_FIELDS);
        names.addAll(ServerSettings.IMAP_FIELDS);
        names.addAll(ServerSettings.GMAIL_FIELDS);
        names.add("MAIL_PROVIDER");
        return new ArrayList<>(names);
    }

    static void resetMail() {
        List<String> names = new ArrayList<>();
        try (Db db = Db.open()) {
            List<String> keys = new ArrayList<>();
            for (String name : mailSettings()) keys.add(ServerSettings.PREFIX + name);
            for (AppSetting row : db.all(AppSetting.class, "WHERE t.`key` IN " + Db.in(keys), keys.toArray())) {
                names.add(row.key.substring(ServerSettings.PREFIX.length()));
                db.delete(row);
            }
            AuthEvent e = new AuthEvent();
            e.username = "#server";
            e.event = "settings_changed";
            e.success = true;
            e.detail = "Mail settings put back to .env (recover mail)";
            e.ipAddress = "";
            e.userAgent = "command line";
            db.add(e);
            db.commit();
        }
        System.out.println("Put back to the .env values: " + (names.isEmpty() ? "nothing (none were changed in the portal)" : String.join(", ", names)) + ".");
        if (Config.gmail()) {
            System.out.println("Now: MAIL_PROVIDER=gmail  GMAIL_ADDRESS=" + Py.or(Config.GMAIL_ADDRESS(), "(empty)")
                    + "  GMAIL_APP_PASSWORD=" + (Config.SMTP_PASSWORD().isEmpty() ? "(empty)" : "(set)"));
        } else {
            System.out.println("Now: MAIL_PROVIDER=smtp  SMTP_HOST=" + Config.SMTP_HOST() + "  SMTP_USER=" + Config.SMTP_USER()
                    + "  MAIL_FROM=" + Config.MAIL_FROM());
        }
        System.out.println("Restart the backend to use them.");
    }

    static void makeCode(String username) {
        String code, name;
        try (Db db = Db.open()) {
            User user = db.first(User.class, "WHERE t.username = ?", Py.strip(username).toLowerCase(java.util.Locale.ROOT));
            if (user == null) exit("No account with the username '" + username + "'.");
            if (!Boolean.TRUE.equals(user.isActive)) exit("The account " + user.username + " is deactivated.");
            if (Py.isEmpty(user.email)) exit("The account " + user.username + " has no email address.");
            db.execute("UPDATE email_verification_codes SET used_at = ? WHERE user_id = ? AND purpose = 'recovery' AND used_at IS NULL",
                    Py.utcnow(), user.id);
            code = Crypto.sixDigits();
            EmailVerificationCode record = new EmailVerificationCode();
            record.userId = user.id;
            record.email = user.email;
            record.purpose = "recovery";
            record.codeHash = Auth.codeHash(user, "recovery", code);
            record.expiresAt = Py.utcnow().plusMinutes(Config.VERIFICATION_CODE_MINUTES());
            db.add(record);
            name = user.username;
            AuthEvent e = new AuthEvent();
            e.userId = user.id;
            e.username = name;
            e.event = "recovery_code_created";
            e.success = true;
            e.detail = "one-time sign-in code made on the server";
            e.ipAddress = "";
            e.userAgent = "command line";
            db.add(e);
            db.commit();
        }
        System.out.println("One-time sign-in code for " + name + ": " + code);
        System.out.println("Valid for " + Config.VERIFICATION_CODE_MINUTES() + " minutes. Sign in with the password, then enter this code.");
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(1);
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("mail")) {
            resetMail();
        } else if (args.length == 2 && args[0].equals("code")) {
            makeCode(args[1]);
        } else {
            System.out.println(USAGE);
            System.exit(1);
        }
    }
}
