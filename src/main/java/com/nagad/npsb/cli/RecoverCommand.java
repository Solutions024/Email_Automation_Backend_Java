package com.nagad.npsb.cli;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.entity.AppSetting;
import com.nagad.npsb.entity.AuthEvent;
import com.nagad.npsb.entity.EmailVerificationCode;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.AppSettingRepository;
import com.nagad.npsb.repository.AuthEventRepository;
import com.nagad.npsb.repository.EmailVerificationCodeRepository;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.service.AuthService;
import com.nagad.npsb.service.ServerSettings;
import com.nagad.npsb.util.Crypto;
import com.nagad.npsb.util.Py;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *
 * Runs only for "recover" (NpsbApplication starts the context without a web server and passes the remaining
 * arguments); the exit code is 1 when the command fails or the arguments are wrong.
 */
@Component
@ConditionalOnProperty(name = "npsb.command", havingValue = "recover")
public class RecoverCommand implements ApplicationRunner, ExitCodeGenerator {

    static final String USAGE = """
            Recovery when the mail server is broken and nobody can get a sign-in code. Run on the server:

                java -jar npsb-dispute-backend.jar recover mail            put the mail server and inbox settings back to the .env values
                                                                           (undoes changes made on the System Settings page); restart the backend
                java -jar npsb-dispute-backend.jar recover code USERNAME   print a one-time sign-in code for this account (valid for
                                                                           VERIFICATION_CODE_MINUTES); nothing is emailed

            Sign in with the code, then fix the settings on the System Settings page. Every use is written to the
            security log.
            """;

    private final UserRepository users;
    private final AppSettingRepository appSettings;
    private final AuthEventRepository authEvents;
    private final EmailVerificationCodeRepository codes;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final LiveSettings live;

    private int exitCode = 0;

    public RecoverCommand(UserRepository users, AppSettingRepository appSettings, AuthEventRepository authEvents,
                          EmailVerificationCodeRepository codes, JdbcClient jdbc, TransactionTemplate tx, LiveSettings live) {
        this.users = users;
        this.appSettings = appSettings;
        this.authEvents = authEvents;
        this.codes = codes;
        this.jdbc = jdbc;
        this.tx = tx;
        this.live = live;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String[] args = arguments.getSourceArgs();
        if (args.length == 1 && args[0].equals("mail")) {
            resetMail();
        } else if (args.length == 2 && args[0].equals("code")) {
            makeCode(args[1]);
        } else {
            System.out.println(USAGE);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    static List<String> mailSettings() {
        TreeSet<String> names = new TreeSet<>();
        names.addAll(ServerSettings.SMTP_FIELDS);
        names.addAll(ServerSettings.IMAP_FIELDS);
        names.addAll(ServerSettings.GMAIL_FIELDS);
        names.add("MAIL_PROVIDER");
        return new ArrayList<>(names);
    }

    void resetMail() {
        List<String> names = tx.execute(status -> {
            List<String> keys = new ArrayList<>();
            for (String name : mailSettings()) keys.add(ServerSettings.PREFIX + name);
            List<String> reset = new ArrayList<>();
            for (AppSetting row : appSettings.findAllById(keys)) {
                reset.add(row.getKey().substring(ServerSettings.PREFIX.length()));
                appSettings.delete(row);
            }
            AuthEvent e = new AuthEvent();
            e.setUsername("#server");
            e.setEvent("settings_changed");
            e.setSuccess(true);
            e.setDetail("Mail settings put back to .env (recover mail)");
            e.setIpAddress("");
            e.setUserAgent("command line");
            authEvents.save(e);
            return reset;
        });
        System.out.println("Put back to the .env values: " + (names.isEmpty() ? "nothing (none were changed in the portal)" : String.join(", ", names)) + ".");
        if (live.gmail()) {
            System.out.println("Now: MAIL_PROVIDER=gmail  GMAIL_ADDRESS=" + Py.or(live.gmailAddress(), "(empty)")
                    + "  GMAIL_APP_PASSWORD=" + (live.smtpPassword().isEmpty() ? "(empty)" : "(set)"));
        } else {
            System.out.println("Now: MAIL_PROVIDER=smtp  SMTP_HOST=" + live.smtpHost() + "  SMTP_USER=" + live.smtpUser()
                    + "  MAIL_FROM=" + live.mailFrom());
        }
        System.out.println("Restart the backend to use them.");
    }

    /** What makeCode did: the error to print, or the account and its code. */
    private record CodeResult(String error, String name, String code) {}

    void makeCode(String username) {
        CodeResult result = tx.execute(status -> {
            User user = users.findByUsername(Py.strip(username).toLowerCase(Locale.ROOT)).orElse(null);
            if (user == null) return new CodeResult("No account with the username '" + username + "'.", null, null);
            if (!Boolean.TRUE.equals(user.getIsActive())) return new CodeResult("The account " + user.getUsername() + " is deactivated.", null, null);
            if (Py.isEmpty(user.getEmail())) return new CodeResult("The account " + user.getUsername() + " has no email address.", null, null);
            jdbc.sql("UPDATE email_verification_codes SET used_at = ? WHERE user_id = ? AND purpose = 'recovery' AND used_at IS NULL")
                    .params(Py.utcnow(), user.getId()).update();
            String code = Crypto.sixDigits();
            EmailVerificationCode record = new EmailVerificationCode();
            record.setUserId(user.getId());
            record.setEmail(user.getEmail());
            record.setPurpose("recovery");
            record.setCodeHash(AuthService.codeHash(user, "recovery", code));
            record.setExpiresAt(Py.utcnow().plusMinutes(live.verificationCodeMinutes()));
            codes.save(record);
            String name = user.getUsername();
            AuthEvent e = new AuthEvent();
            e.setUserId(user.getId());
            e.setUsername(name);
            e.setEvent("recovery_code_created");
            e.setSuccess(true);
            e.setDetail("one-time sign-in code made on the server");
            e.setIpAddress("");
            e.setUserAgent("command line");
            authEvents.save(e);
            return new CodeResult(null, name, code);
        });
        if (result.error() != null) {
            System.err.println(result.error());
            exitCode = 1;
            return;
        }
        System.out.println("One-time sign-in code for " + result.name() + ": " + result.code());
        System.out.println("Valid for " + live.verificationCodeMinutes() + " minutes. Sign in with the password, then enter this code.");
    }
}
