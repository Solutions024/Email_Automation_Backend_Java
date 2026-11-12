package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.config.NpsbProperties;
import com.nagad.npsb.dto.auth.ChangePasswordRequest;
import com.nagad.npsb.dto.auth.EmailCodeRequest;
import com.nagad.npsb.dto.auth.ForgotPasswordRequest;
import com.nagad.npsb.dto.auth.LoginRequest;
import com.nagad.npsb.dto.auth.ProfileUpdateRequest;
import com.nagad.npsb.dto.auth.RegisterRequest;
import com.nagad.npsb.dto.auth.ResendCodeRequest;
import com.nagad.npsb.dto.auth.ResetPasswordRequest;
import com.nagad.npsb.dto.auth.VerifyCodeRequest;
import com.nagad.npsb.entity.AppSetting;
import com.nagad.npsb.entity.AuthEvent;
import com.nagad.npsb.entity.EmailVerificationCode;
import com.nagad.npsb.entity.PendingRegistration;
import com.nagad.npsb.entity.RefreshToken;
import com.nagad.npsb.entity.TrustedDevice;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.AppSettingRepository;
import com.nagad.npsb.repository.AuthEventRepository;
import com.nagad.npsb.repository.EmailVerificationCodeRepository;
import com.nagad.npsb.repository.PendingRegistrationRepository;
import com.nagad.npsb.repository.RefreshTokenRepository;
import com.nagad.npsb.repository.TrustedDeviceRepository;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.security.CurrentUser;
import com.nagad.npsb.security.JwtService;
import com.nagad.npsb.service.auth.CommitThenRaise;
import com.nagad.npsb.service.auth.RateLimiter;
import com.nagad.npsb.util.Crypto;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.util.PyJson;
import com.nagad.npsb.web.ApiException;
import com.nagad.npsb.web.BackgroundTasks;
import com.nagad.npsb.web.ClientInfo;
import com.nagad.npsb.web.Cookies;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Authentication: accounts, sign-in, sessions and password recovery.
 *
 * How sign-in works
 *   * Two steps: POST /api/auth/login checks the password and emails a 6-digit code; POST /api/auth/verify-code
 *     checks the code and returns a short-lived JWT access token (15 min). Exception: after a sign-in with the
 *     code and "Keep me signed in", that browser is a trusted device for 30 days, and signing in there with
 *     "Keep me signed in" needs only the password. Unticking the box, changing/resetting the password or "sign
 *     out everywhere" ends it. Registering works the same way: the account is created by entering the emailed code.
 *   * It also sets a long-lived refresh token in an httpOnly cookie. When the access token expires,
 *     POST /api/auth/refresh exchanges the cookie for a new access token. Refresh tokens are rotated on every use;
 *     re-using an old one revokes the whole session (it was probably stolen).
 *   * Each refresh-token chain is one "session" (browser/device) that the user can see and sign out of.
 *     Changing or resetting the password signs out every session.
 *
 * Two roles: "admin" and "user". There is no self sign-up: the built-in admin ("admin") creates the other
 * admins and users, and admins create accounts after that. Only admins manage users
 * and send emails (requireAdmin); users can do everything else, including partners and follow-ups.
 *
 * Protection: PBKDF2-SHA256 passwords (600k iterations), lock-out after wrong passwords, rate limits per IP,
 * tokens stored only as SHA-256 hashes, and every sign-in, failure, sign-out and change in auth_events.
 *
 * Transactions: each endpoint method is one transaction (the handler's db.commit()). Where the handler recorded a
 * failed attempt and committed before answering with an error, the error is thrown as {@link CommitThenRaise}:
 * the changes so far are committed, then the error is answered. Any other error rolls everything back.
 * The public helpers other parts call (logEvent, revokeAllSessions, accountConflict, ...) join the caller's
 * transaction. Cookies are written to the response the controller passes in (held back until success).
 */
@Service
public class AuthService {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    public static final int PBKDF2_ITERATIONS = 600_000;
    public static final String REFRESH_COOKIE = "npsb_refresh";
    /** Path of the refresh and trusted-device cookies below the context path (cookiePath adds the context path). */
    public static final String REFRESH_COOKIE_PATH = "/api/auth";
    public static final String TRUSTED_COOKIE = "npsb_trusted";  // trusted device: password-only sign-in (same path as the refresh cookie)
    public static final int REFRESH_REUSE_GRACE_SECONDS = 30;    // two tabs refreshing at the same moment is not theft
    public static final int MAX_LOGIN_CODES = 5;  // codes per sign-in within VERIFICATION_CODE_MINUTES, counting resends

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "12345678", "123456789", "1234567890",
            "qwerty123", "qwertyuiop", "11111111", "iloveyou", "admin123", "welcome1",
            "letmein1", "abc12345", "p@ssw0rd", "passw0rd");

    private static final String EXPIRED_CODE = "This code has expired or was replaced. Request a new code.";

    private final UserRepository users;
    private final AuthEventRepository authEvents;
    private final EmailVerificationCodeRepository codes;
    private final PendingRegistrationRepository pendingRegistrations;
    private final RefreshTokenRepository refreshTokens;
    private final TrustedDeviceRepository trustedDevices;
    private final AppSettingRepository appSettings;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;
    private final LiveSettings live;
    private final NpsbProperties properties;
    private final JwtService jwt;
    private final CurrentUser currentUser;
    private final EmailService emailService;
    private final EmailCheck emailCheck;
    private final BackgroundTasks backgroundTasks;

    private final RateLimiter loginLimiter;  // System Settings can change it
    private final RateLimiter registerLimiter = new RateLimiter(() -> 5, 60);
    private final RateLimiter forgotLimiter = new RateLimiter(() -> 5, 60);
    private final RateLimiter verifyLimiter = new RateLimiter(() -> 10, 60);
    private final RateLimiter resendLimiter = new RateLimiter(() -> 5, 60);
    /** Context path + REFRESH_COOKIE_PATH, e.g. /email-automation-backend/api/auth. */
    private final String cookiePath;

    public AuthService(UserRepository users, AuthEventRepository authEvents, EmailVerificationCodeRepository codes,
                       PendingRegistrationRepository pendingRegistrations, RefreshTokenRepository refreshTokens,
                       TrustedDeviceRepository trustedDevices, AppSettingRepository appSettings, JdbcClient jdbc,
                       EntityManager entityManager, TransactionTemplate tx, LiveSettings live, NpsbProperties properties,
                       JwtService jwt, CurrentUser currentUser, EmailService emailService, EmailCheck emailCheck,
                       BackgroundTasks backgroundTasks,
                       @Value("${server.servlet.context-path:}") String contextPath) {
        // the browser only sends the cookies back to this path, so it includes the context path
        String base = contextPath == null ? "" : contextPath.replaceAll("/+$", "");
        this.cookiePath = base + REFRESH_COOKIE_PATH;
        this.users = users;
        this.authEvents = authEvents;
        this.codes = codes;
        this.pendingRegistrations = pendingRegistrations;
        this.refreshTokens = refreshTokens;
        this.trustedDevices = trustedDevices;
        this.appSettings = appSettings;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.tx = tx;
        this.live = live;
        this.properties = properties;
        this.jwt = jwt;
        this.currentUser = currentUser;
        this.emailService = emailService;
        this.emailCheck = emailCheck;
        this.backgroundTasks = backgroundTasks;
        this.loginLimiter = new RateLimiter(live::loginRateLimitPerMinute, 60);
    }

    // ================================================================ helpers

    /** Stored as pbkdf2_sha256$iterations$salt$hash. */
    public static String hashPassword(String password) {
        String salt = Crypto.tokenHex(16);
        return "pbkdf2_sha256$" + PBKDF2_ITERATIONS + "$" + salt + "$" + Crypto.pbkdf2Hex(password, salt, PBKDF2_ITERATIONS);
    }

    public static boolean verifyPassword(String password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$", -1);
        if (parts.length != 4) return false;
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        return Crypto.equal(Crypto.pbkdf2Hex(password, parts[2], iterations), parts[3]);
    }

    /** Checked when the account does not exist, so a wrong username takes as long as a wrong password */
    private static final String DUMMY_HASH = hashPassword(Crypto.tokenHex(16));

    /** Strength rules. The frontend shows the same rules live while typing. */
    public static List<String> passwordProblems(String password, String username, String email) {
        List<String> problems = new ArrayList<>();
        if (password.length() < 8) problems.add("at least 8 characters");
        if (password.length() > 128) problems.add("at most 128 characters");
        if (!password.matches("(?s).*[a-z].*")) problems.add("a lowercase letter");
        if (!password.matches("(?s).*[A-Z].*")) problems.add("an uppercase letter");
        if (!Pattern.compile("\\d", Pattern.UNICODE_CHARACTER_CLASS).matcher(password).find()) problems.add("a number");
        if (!password.matches("(?s).*[^A-Za-z0-9].*")) problems.add("a symbol");
        String lowered = password.toLowerCase(Locale.ROOT);
        if (username != null && username.length() >= 3 && lowered.contains(username.toLowerCase(Locale.ROOT))) {
            problems.add("must not contain the username");
        }
        String localPart = email != null && !email.isEmpty() ? email.split("@", -1)[0].toLowerCase(Locale.ROOT) : "";
        if (localPart.length() >= 3 && lowered.contains(localPart)) problems.add("must not contain the email address");
        if (COMMON_PASSWORDS.contains(lowered)) problems.add("must not be a common password");
        return problems;
    }

    public static void checkPasswordStrength(String password, String username, String email) {
        List<String> problems = passwordProblems(password, username, email);
        if (!problems.isEmpty()) throw new ApiException(400, "Password is too weak: " + String.join(", ", problems) + ".");
    }

    public static String hashToken(String token) {
        return Crypto.sha256Hex(token);
    }

    /** A row in the security log (auth_events), saved with the caller's transaction. IP and browser: the current request. */
    public void logEvent(String event, User user, String username, boolean success, String detail) {
        AuthEvent e = new AuthEvent();
        e.setUserId(user != null ? user.getId() : null);
        e.setUsername(Py.cut(user != null ? user.getUsername() : username == null ? "" : username, 254));
        e.setEvent(event);
        e.setSuccess(success);
        e.setDetail(Py.cut(detail == null ? "" : detail, 255));
        e.setIpAddress(ClientInfo.clientIp());
        e.setUserAgent(ClientInfo.userAgent());
        authEvents.save(e);
    }

    public void logEvent(String event, User user, String detail) {
        logEvent(event, user, "", true, detail);
    }

    public static Map<String, Object> userToDict(User user) {
        LocalDateTime now = Py.utcnow();
        boolean locked = user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
        return Py.map(
                "id", user.getId(),
                "username", user.getUsername(),
                "email", user.getEmail(),
                "full_name", user.getFullName(),
                "role", user.getRole(),
                "is_built_in", isBuiltInAdmin(user),
                "email_verified", user.getEmailVerified(),
                "pending_email", user.getPendingEmail(),
                "is_active", user.getIsActive(),
                "is_locked", locked,
                "locked_until", locked ? Py.isoUtc(user.getLockedUntil()) : null,
                "last_login_at", Py.isoUtc(user.getLastLoginAt()),
                "password_changed_at", Py.isoUtc(user.getPasswordChangedAt()),
                "created_at", Py.isoUtc(user.getCreatedAt()));
    }

    /**
     * One transaction (the handler's db.commit()). A {@link CommitThenRaise} thrown inside commits the changes made
     * so far and is then thrown (the old handlers committed, then raised); any other exception rolls back.
     */
    private <T> T transaction(Supplier<T> work) {
        CommitThenRaise[] raised = {null};
        T result = tx.execute(status -> {
            try {
                return work.get();
            } catch (CommitThenRaise e) {
                raised[0] = e;
                return null;
            }
        });
        if (raised[0] != null) throw raised[0];
        return result;
    }

    /** A cookie of the current request, or null. */
    private static String requestCookie(String name) {
        HttpServletRequest request = ClientInfo.currentRequest();
        return request == null ? null : ClientInfo.cookie(request, name);
    }

    // ---------------------------------------------------------------- tokens

    /** Create a refresh token, store its hash, and put the raw token in the httpOnly cookie. */
    RefreshToken startSession(User user, boolean rememberMe, String familyId, HttpServletResponse response) {
        String raw = Crypto.tokenUrlsafe(48);
        int days = rememberMe ? live.refreshTokenRememberDays() : live.refreshTokenDays();
        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setTokenHash(hashToken(raw));
        token.setFamilyId(familyId != null ? familyId : Crypto.tokenHex(16));
        token.setRememberMe(rememberMe);
        token.setUserAgent(ClientInfo.userAgent());
        token.setIpAddress(ClientInfo.clientIp());
        token.setExpiresAt(Py.utcnow().plusDays(days));
        refreshTokens.save(token);
        entityManager.flush();
        // without "remember me": gone when the browser closes
        Cookies.set(response, REFRESH_COOKIE, raw, rememberMe ? days * 86400 : null, true, properties.isCookieSecure(),
                "strict", cookiePath);
        return token;
    }

    void clearRefreshCookie(HttpServletResponse response) {
        Cookies.delete(response, REFRESH_COOKIE, cookiePath, true, properties.isCookieSecure(), "strict");
    }

    /** Sign out every session of this user (the refresh tokens stop working). */
    public void revokeAllSessions(User user) {
        revokeAllSessions(user, null);
    }

    public void revokeAllSessions(User user, String exceptFamily) {
        if (exceptFamily != null) {
            jdbc.sql("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL AND family_id != ?")
                    .params(Py.utcnow(), user.getId(), exceptFamily).update();
        } else {
            jdbc.sql("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                    .params(Py.utcnow(), user.getId()).update();
        }
    }

    /** Remember this browser: for the next REFRESH_TOKEN_REMEMBER_DAYS it signs in without a code. */
    void trustDevice(User user, HttpServletResponse response) {
        String raw = Crypto.tokenUrlsafe(48);
        int days = live.refreshTokenRememberDays();
        TrustedDevice d = new TrustedDevice();
        d.setUserId(user.getId());
        d.setTokenHash(hashToken(raw));
        d.setUserAgent(ClientInfo.userAgent());
        d.setIpAddress(ClientInfo.clientIp());
        d.setExpiresAt(Py.utcnow().plusDays(days));
        trustedDevices.save(d);
        Cookies.set(response, TRUSTED_COOKIE, raw, days * 86400, true, properties.isCookieSecure(), "strict", cookiePath);
    }

    /** This browser's trusted-device record for this user, if it is still valid. */
    TrustedDevice trustedDevice(User user) {
        String raw = requestCookie(TRUSTED_COOKIE);
        if (Py.isEmpty(raw)) return null;
        return trustedDevices.findFirstByTokenHashAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(hashToken(raw), user.getId(), Py.utcnow())
                .orElse(null);
    }

    /** Stop trusting this browser (it needs the emailed code again). */
    void forgetDevice(HttpServletResponse response) {
        String raw = requestCookie(TRUSTED_COOKIE);
        if (!Py.isEmpty(raw)) {
            jdbc.sql("UPDATE trusted_devices SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")
                    .params(Py.utcnow(), hashToken(raw)).update();
            Cookies.delete(response, TRUSTED_COOKIE, cookiePath, true, properties.isCookieSecure(), "strict");
        }
    }

    /** Every browser of this user needs the emailed code again. */
    public void revokeTrustedDevices(User user) {
        jdbc.sql("UPDATE trusted_devices SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                .params(Py.utcnow(), user.getId()).update();
    }

    RefreshToken currentRefreshToken() {
        String raw = requestCookie(REFRESH_COOKIE);
        if (Py.isEmpty(raw)) return null;
        return refreshTokens.findFirstByTokenHash(hashToken(raw)).orElse(null);
    }

    Map<String, Object> tokenResponse(User user) {
        return Py.map(
                "access_token", jwt.createAccessToken(user),
                "token_type", "bearer",
                "expires_in", live.accessTokenMinutes() * 60,
                "user", userToDict(user));
    }

    // ---------------------------------------------------------------- dependencies

    /** Admin accounts can only be created with the right admin code. */
    void checkAdminCode(String role, String code, String username) {
        if ("admin".equals(role) && !Crypto.equal(Py.strip(code), live.adminSignupCode())) {
            logEvent("admin_code_failed", null, username, false, "wrong admin code for a new admin account");
            throw new CommitThenRaise(403, "Incorrect admin code. Leave the role as User, or ask an admin for the code.");
        }
    }

    // ---------------------------------------------------------------- email verification codes

    EmailVerificationCode latestCode(User user, String purpose, String email) {
        return codes.findFirstByUserIdAndPurposeAndEmailAndUsedAtIsNullOrderByIdDesc(user.getId(), purpose, email).orElse(null);
    }

    public static String codeHash(User user, String purpose, String code) {
        return hashToken(user.getId() + ":" + purpose + ":" + code);
    }

    /**
     * Email a fresh 6-digit code (older codes stop working). Returns false if one was sent less than
     * VERIFICATION_RESEND_SECONDS ago. 502 if the email cannot be sent.
     */
    boolean sendVerificationCode(User user, String email, String purpose, boolean respectCooldown) {
        EmailVerificationCode previous = latestCode(user, purpose, email);
        if (respectCooldown && previous != null
                && Duration.between(previous.getCreatedAt(), Py.utcnow()).getSeconds() < NpsbProperties.VERIFICATION_RESEND_SECONDS) {
            return false;
        }
        jdbc.sql("UPDATE email_verification_codes SET used_at = ? WHERE user_id = ? AND purpose = ? AND used_at IS NULL")
                .params(Py.utcnow(), user.getId(), purpose).update();
        String code = Crypto.sixDigits();
        EmailVerificationCode record = new EmailVerificationCode();
        record.setUserId(user.getId());
        record.setEmail(email);
        record.setPurpose(purpose);
        record.setCodeHash(codeHash(user, purpose, code));
        record.setExpiresAt(Py.utcnow().plusMinutes(live.verificationCodeMinutes()));
        codes.save(record);
        entityManager.flush();
        try {
            deliverCodeEmail(email, Py.or(user.getFullName(), user.getUsername()), code, purpose);
        } catch (Exception exc) {
            throw new ApiException(502, "We could not send a verification email to " + email + ". Check that the "
                    + "address is correct and try again. (" + exc.getMessage() + ")");
        }
        return true;
    }

    void deliverCodeEmail(String email, String name, String code, String purpose) {
        String action = "change".equals(purpose) ? "confirm your new email address"
                : "reset".equals(purpose) ? "reset your password" : "sign in";
        String body = "\n    <div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#1f2430;max-width:520px\">\n"
                + "      <p>Hello " + Py.escape(name) + ",</p>\n"
                + "      <p>Use this code to " + action + " for NPSB Dispute Email Automation:</p>\n"
                + "      <p style=\"font-size:30px;font-weight:700;letter-spacing:8px;margin:20px 0;color:#e8222d\">" + code + "</p>\n"
                + "      <p>The code is valid for " + live.verificationCodeMinutes() + " minutes. If you did not request it,\n"
                + "         you can ignore this email" + ("reset".equals(purpose) ? "; your password stays the same" : "") + ".</p>\n"
                + "    </div>";
        String subject = "login".equals(purpose) ? "Your sign-in code" : "reset".equals(purpose) ? "Your password reset code"
                : "Your verification code";
        emailService.sendEmail(List.of(email), List.of(), subject + ": " + code, body, null, null, null);
    }

    /** 400 unless `code` is the current, unexpired code for this address (a wrong code is counted and committed). */
    void checkCode(User user, String purpose, String email, String code) {
        EmailVerificationCode record = latestCode(user, purpose, email);
        if (record == null || !record.getExpiresAt().isAfter(Py.utcnow())
                || record.getAttempts() >= NpsbProperties.VERIFICATION_MAX_ATTEMPTS) {
            throw new ApiException(400, EXPIRED_CODE);
        }
        if (!Crypto.equal(record.getCodeHash(), codeHash(user, purpose, Py.strip(code)))) {
            record.setAttempts(record.getAttempts() + 1);
            int left = NpsbProperties.VERIFICATION_MAX_ATTEMPTS - record.getAttempts();
            if (left <= 0) throw new CommitThenRaise(400, "Too many wrong codes. Request a new code.");
            throw new CommitThenRaise(400, "Incorrect code. " + left + " attempt(s) left.");
        }
        record.setUsedAt(Py.utcnow());
    }

    /** A one-time code made on the server (recover code USERNAME) for when email is broken. */
    EmailVerificationCode validRecoveryCode(User user) {
        EmailVerificationCode record = latestCode(user, "recovery", Py.or(user.getEmail(), ""));
        if (record != null && record.getExpiresAt().isAfter(Py.utcnow())
                && record.getAttempts() < NpsbProperties.VERIFICATION_MAX_ATTEMPTS) {
            return record;
        }
        return null;
    }

    /**
     * Password OK (or account just created): email the code and tell the page to ask for it. With a recovery
     * code waiting, nothing is emailed: the code from the server is asked for instead.
     */
    Map<String, Object> sendLoginCode(User user) {
        if (validRecoveryCode(user) != null) {
            logEvent("login_code_sent", user, "recovery code from the server, no email");
            return Py.map("otp_required", true, "login", user.getUsername(), "email", user.getEmail(),
                    "message", "Enter the one-time recovery code created on the server.");
        }
        boolean sent = sendVerificationCode(user, user.getEmail(), "login", true);
        logEvent("login_code_sent", user, sent ? "" : "recent code still valid");
        String note = sent ? "We've sent a 6-digit code to" : "Enter the 6-digit code we sent to";
        return Py.map("otp_required", true, "login", user.getUsername(), "email", user.getEmail(),
                "message", note + " " + user.getEmail() + ".");
    }

    User findUser(String login) {
        return (login.contains("@") ? users.findByEmail(login) : users.findByUsername(login)).orElse(null);
    }

    // ---------------------------------------------------------------- accounts only exist once created
    // A self sign-up only becomes an account when its emailed code is entered (pending_registrations until then).
    // Accounts made by the old sign-up and never confirmed or used are "abandoned": they don't count as taking a
    // username or an email, and are removed when a real account needs the name or address.

    boolean isAbandoned(User user) {
        if (Boolean.TRUE.equals(user.getEmailVerified()) || user.getLastLoginAt() != null) return false;
        return !jdbc.sql("SELECT id FROM auth_events WHERE user_id = ? AND event = 'register' LIMIT 1")
                .params(user.getId()).query((rs, rowNum) -> rs.getObject(1)).list().isEmpty();
    }

    /** Why this username / email can't be used (a real account has it), or null. */
    public String accountConflict(String username, String email, Integer exceptId) {
        int except = exceptId == null ? 0 : exceptId;
        if (!Py.isEmpty(username)) {
            for (User other : users.findByUsernameAndIdNot(username, except)) {
                if (!isAbandoned(other)) return "This username is already taken.";
            }
        }
        if (!Py.isEmpty(email)) {
            for (User other : users.findByEmailAndIdNot(email, except)) {
                if (!isAbandoned(other)) return "An account with this email already exists.";
            }
        }
        return null;
    }

    /** Remove abandoned sign-ups holding this username / email, so a real account can use it. */
    public void purgeAbandoned(String username, String email) {
        boolean byUsername = !Py.isEmpty(username), byEmail = !Py.isEmpty(email);
        List<User> holders;
        if (byUsername && byEmail) holders = users.findByUsernameOrEmail(username, email);
        else if (byUsername) holders = users.findByUsername(username).stream().toList();
        else if (byEmail) holders = users.findByEmail(email).stream().toList();
        else return;
        for (User user : holders) {
            if (!isAbandoned(user)) continue;
            for (String table : new String[]{"email_verification_codes", "refresh_tokens", "trusted_devices", "password_reset_tokens"}) {
                jdbc.sql("DELETE FROM " + table + " WHERE user_id = ?").params(user.getId()).update();
            }
            jdbc.sql("UPDATE auth_events SET user_id = NULL WHERE user_id = ?").params(user.getId()).update();
            users.delete(user);
        }
        entityManager.flush();  // before the new account is inserted (the names are unique)
    }

    long realUserCount() {
        long n = 0;
        for (User u : users.findAll()) if (!isAbandoned(u)) n++;
        return n;
    }

    static String registrationCodeHash(PendingRegistration pending, String code) {
        return hashToken("registration:" + pending.getId() + ":" + code);
    }

    /** The newest unfinished sign-up for this username or email that has not expired. */
    PendingRegistration pendingRegistration(String login) {
        LocalDateTime now = Py.utcnow();
        return (login.contains("@")
                ? pendingRegistrations.findFirstByEmailAndExpiresAtAfterOrderByIdDesc(login, now)
                : pendingRegistrations.findFirstByUsernameAndExpiresAtAfterOrderByIdDesc(login, now)).orElse(null);
    }

    // ================================================================ the built-in admin

    /** No self sign-up: the built-in admin (ensureBuiltInAdmin) creates every account. */
    static boolean registrationOpen() {
        return false;
    }

    public static boolean isBuiltInAdmin(User user) {
        return user != null && NpsbProperties.ADMIN_USERNAME.equals(user.getUsername()) && "admin".equals(user.getRole());
    }

    /** Accounts (create, passwords, roles, activation) are managed by the built-in admin only, not other admins. */
    public User requireBuiltInAdmin() {
        User user = currentUser.require();
        if (!isBuiltInAdmin(user)) throw new ApiException(403, "Only the built-in admin can manage accounts.");
        return user;
    }

    /** Done once: the built-in admin was (re)set and every other account was switched off. */
    private static final String BUILT_IN_RESET_KEY = "builtin_admin_reset_v1";

    /**
     * At startup: make sure the built-in admin exists. The first time (BUILT_IN_RESET_KEY), it is given the
     * standard password and every other account is deactivated and signed out, so only the built-in admin can
     * sign in until it creates (or reactivates) the other accounts on Users & Security.
     */
    public void ensureBuiltInAdmin() {
        User admin = tx.execute(status -> {
            boolean firstTime = appSettings.findById(BUILT_IN_RESET_KEY).isEmpty();
            User a = users.findByUsername(NpsbProperties.ADMIN_USERNAME).orElse(null);
            boolean created = a == null;
            if (created) {
                a = new User();
                a.setUsername(NpsbProperties.ADMIN_USERNAME);
                a.setFullName("Administrator");
            }
            if (created || firstTime) {
                a.setRole("admin");
                a.setIsActive(true);
                a.setPasswordHash(hashPassword(NpsbProperties.ADMIN_PASSWORD));
                a.setPasswordChangedAt(Py.utcnow());
                a.setFailedLoginCount(0);
                a.setLockedUntil(null);
            }
            if (Py.isEmpty(a.getEmail())) {  // where its sign-in codes go; none: it signs in with the password only
                String email = Py.or(properties.adminEmailAddress(), Py.strip(Py.or(live.mailboxAddress(), ""))).toLowerCase(Locale.ROOT);
                if (!email.isEmpty() && users.findFirstByEmailAndUsernameNot(email, a.getUsername()).isEmpty()) {
                    a.setEmail(email);
                    a.setEmailVerified(true);
                    a.setEmailVerifiedAt(Py.utcnow());
                } else {
                    a.setEmail(null);
                }
            }
            if (created) users.save(a);
            entityManager.flush();
            if (firstTime) {
                if (!created) {  // old sessions of the account are ended (new password)
                    a.setTokenVersion(a.getTokenVersion() + 1);
                    revokeAllSessions(a);
                    revokeTrustedDevices(a);
                }
                int off = 0;
                for (User other : users.findByIdNotAndIsActive(a.getId(), true)) {
                    other.setIsActive(false);
                    other.setTokenVersion(other.getTokenVersion() + 1);
                    revokeAllSessions(other);
                    revokeTrustedDevices(other);
                    off++;
                }
                AppSetting flag = new AppSetting();
                flag.setKey(BUILT_IN_RESET_KEY);
                flag.setValue(PyJson.dumps(Py.isoSeconds(Py.now())));
                flag.setUpdatedBy("#startup");
                appSettings.save(flag);
                LOG.info("Built-in admin '" + a.getUsername() + "' is set up; " + off + " other account(s) were deactivated "
                        + "until the admin reactivates them.");
            }
            return a;
        });
        if (admin.getEmail() == null) {
            LOG.warn("The built-in admin has no email address, so it signs in with its password only. Set ADMIN_EMAIL in .env "
                    + "(or set up the Gmail account) to get sign-in codes.");
        }
    }

    // ================================================================ endpoints (AuthController)

    /** GET /api/auth/settings */
    public Map<String, Object> authSettings() {
        return Py.map("allow_registration", registrationOpen(), "first_user", realUserCount() == 0,
                "remember_me_days", live.refreshTokenRememberDays());
    }

    /** POST /api/auth/register */
    public Map<String, Object> register(RegisterRequest in) {
        registerLimiter.check(ClientInfo.clientIp());
        if (!registrationOpen()) throw new ApiException(403, "There is no sign-up. Ask the admin to create an account for you.");
        return transaction(() -> {
            String username = in.username(), email = in.email(), role = in.role();
            checkAdminCode(role, in.adminCode(), username);
            checkPasswordStrength(in.password(), username, email);
            String conflict = accountConflict(username, email, null);
            if (conflict != null) throw new ApiException(409, conflict);
            emailCheck.validateRealEmail(email);

            // Nothing is created yet: the account is made when the emailed code is entered (verify-code)
            LocalDateTime now = Py.utcnow();
            jdbc.sql("DELETE FROM pending_registrations WHERE expires_at <= ? OR email = ?").params(now, email).update();
            PendingRegistration pending = new PendingRegistration();
            pending.setUsername(username);
            pending.setEmail(email);
            pending.setFullName(in.fullName());
            pending.setRole(role);
            pending.setPasswordHash(hashPassword(in.password()));
            pending.setCodeHash("");
            pending.setExpiresAt(now.plusMinutes(live.verificationCodeMinutes()));
            pendingRegistrations.save(pending);
            entityManager.flush();
            String code = Crypto.sixDigits();
            pending.setCodeHash(registrationCodeHash(pending, code));
            try {
                deliverCodeEmail(pending.getEmail(), Py.or(pending.getFullName(), pending.getUsername()), code, "login");
            } catch (Exception exc) {  // nothing is kept (rolled back)
                throw new ApiException(502, "We could not send a verification email to " + email + ". Check that the "
                        + "address is correct and try again. (" + exc.getMessage() + ")");
            }
            logEvent("registration_started", null, username, true, "role: " + role);
            return Py.map("otp_required", true, "registration", true, "login", pending.getUsername(), "email", pending.getEmail(),
                    "message", "We've sent a 6-digit code to " + pending.getEmail() + ". Your account is created when you enter it.");
        });
    }

    /** The sign-up's code is right: create the account and sign in, in one transaction. */
    private Map<String, Object> finishRegistration(PendingRegistration pending, boolean rememberMe, HttpServletResponse response) {
        String conflict = accountConflict(pending.getUsername(), pending.getEmail(), null);
        if (conflict != null) {  // someone else finished a sign-up with this name or address first
            pendingRegistrations.delete(pending);
            throw new CommitThenRaise(409, conflict + " Someone finished creating it first: create your account again.");
        }
        purgeAbandoned(pending.getUsername(), pending.getEmail());
        LocalDateTime now = Py.utcnow();
        User user = new User();
        user.setUsername(pending.getUsername());
        user.setEmail(pending.getEmail());
        user.setFullName(pending.getFullName());
        user.setRole(pending.getRole());
        user.setPasswordHash(pending.getPasswordHash());
        user.setPasswordChangedAt(now);
        user.setEmailVerified(true);
        user.setEmailVerifiedAt(now);
        user.setLastLoginAt(now);
        users.save(user);
        pendingRegistrations.delete(pending);
        entityManager.flush();
        logEvent("register", user, "role: " + user.getRole() + "; email confirmed with the code");
        startSession(user, rememberMe, null, response);
        if (rememberMe) trustDevice(user, response);
        logEvent("login_success", user, "new account");
        return tokenResponse(user);
    }

    private void checkRegistrationCode(PendingRegistration pending, String code) {
        if (pending == null || pending.getAttempts() >= NpsbProperties.VERIFICATION_MAX_ATTEMPTS) {
            throw new ApiException(400, EXPIRED_CODE);
        }
        if (!Crypto.equal(pending.getCodeHash(), registrationCodeHash(pending, Py.strip(code)))) {
            pending.setAttempts(pending.getAttempts() + 1);
            int left = NpsbProperties.VERIFICATION_MAX_ATTEMPTS - pending.getAttempts();
            logEvent("login_code_failed", null, pending.getUsername(), false, "sign-up code");
            if (left <= 0) throw new CommitThenRaise(400, "Too many wrong codes. Request a new code.");
            throw new CommitThenRaise(400, "Incorrect code. " + left + " attempt(s) left.");
        }
    }

    /** POST /api/auth/verify-code: step 2 of sign-in, the emailed code is correct -> the user is signed in. */
    public Map<String, Object> verifyCode(VerifyCodeRequest in, HttpServletResponse response) {
        verifyLimiter.check(ClientInfo.clientIp());
        if (in.registration()) {  // a new sign-up: the account is created now
            return transaction(() -> {
                PendingRegistration pending = pendingRegistration(in.login());
                checkRegistrationCode(pending, in.code());
                return finishRegistration(pending, in.rememberMe(), response);
            });
        }
        return transaction(() -> {
            User user = findUser(in.login());
            if (user == null || Py.isEmpty(user.getEmail())) throw new ApiException(400, EXPIRED_CODE);
            if (!Boolean.TRUE.equals(user.getIsActive())) throw new ApiException(403, "This account has been disabled.");
            try {
                checkCode(user, validRecoveryCode(user) != null ? "recovery" : "login", user.getEmail(), in.code());
            } catch (ApiException e) {
                logEvent("login_code_failed", user, "", false, e.text());
                throw CommitThenRaise.of(e);
            }
            if (!Boolean.TRUE.equals(user.getEmailVerified())) {  // the code reached this inbox, so the address is real
                user.setEmailVerified(true);
                user.setEmailVerifiedAt(Py.utcnow());
            }
            user.setLastLoginAt(Py.utcnow());
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            startSession(user, in.rememberMe(), null, response);
            if (in.rememberMe()) trustDevice(user, response);  // next time: password only on this browser
            logEvent("login_success", user, in.rememberMe() ? "remember me; device trusted" : "");
            return tokenResponse(user);
        });
    }

    /** POST /api/auth/resend-code: a new sign-in code (only while a sign-in is in progress). */
    public Map<String, Object> resendCode(ResendCodeRequest in) {
        resendLimiter.check(ClientInfo.clientIp());
        if (in.registration()) return resendRegistrationCode(in.login());
        return transaction(() -> {
            User user = findUser(in.login());
            ApiException startOver = new ApiException(400, "Your sign-in has expired. Please sign in again.");
            if (user == null || Py.isEmpty(user.getEmail()) || !Boolean.TRUE.equals(user.getIsActive())) throw startOver;
            LocalDateTime windowStart = Py.utcnow().minusMinutes(live.verificationCodeMinutes());
            long recent = jdbc.sql("SELECT COUNT(*) FROM email_verification_codes WHERE user_id = ? AND purpose = 'login' AND created_at > ?")
                    .params(user.getId(), windowStart).query(Long.class).single();
            if (recent == 0) throw startOver;
            if (recent >= MAX_LOGIN_CODES) throw new ApiException(429, "Too many codes requested. Please wait a few minutes and sign in again.");
            if (!sendVerificationCode(user, user.getEmail(), "login", true)) {
                throw new ApiException(429, "Please wait " + NpsbProperties.VERIFICATION_RESEND_SECONDS + " seconds before asking for another code.");
            }
            logEvent("login_code_sent", user, "resent");
            return Py.map("message", "A new code has been sent to " + user.getEmail() + ".",
                    "resend_after", NpsbProperties.VERIFICATION_RESEND_SECONDS);
        });
    }

    private Map<String, Object> resendRegistrationCode(String login) {
        return transaction(() -> {
            PendingRegistration pending = pendingRegistration(login);
            if (pending == null) throw new ApiException(400, "Your sign-up has expired. Please create your account again.");
            if (Duration.between(pending.getSentAt(), Py.utcnow()).getSeconds() < NpsbProperties.VERIFICATION_RESEND_SECONDS) {
                throw new ApiException(429, "Please wait " + NpsbProperties.VERIFICATION_RESEND_SECONDS + " seconds before asking for another code.");
            }
            if (pending.getCodesSent() >= MAX_LOGIN_CODES) {
                throw new ApiException(429, "Too many codes requested. Please create your account again in a few minutes.");
            }
            String code = Crypto.sixDigits();
            try {
                deliverCodeEmail(pending.getEmail(), Py.or(pending.getFullName(), pending.getUsername()), code, "login");
            } catch (Exception exc) {
                throw new ApiException(502, "We could not send a verification email to " + pending.getEmail() + ". (" + exc.getMessage() + ")");
            }
            LocalDateTime now = Py.utcnow();
            pending.setCodeHash(registrationCodeHash(pending, code));
            pending.setAttempts(0);
            pending.setSentAt(now);
            pending.setCodesSent(pending.getCodesSent() + 1);
            pending.setExpiresAt(now.plusMinutes(live.verificationCodeMinutes()));
            logEvent("login_code_sent", null, pending.getUsername(), true, "sign-up code resent");
            return Py.map("message", "A new code has been sent to " + pending.getEmail() + ".",
                    "resend_after", NpsbProperties.VERIFICATION_RESEND_SECONDS);
        });
    }

    /** POST /api/auth/login: step 1 of sign-in. */
    public Map<String, Object> login(LoginRequest in, HttpServletResponse response) {
        loginLimiter.check(ClientInfo.clientIp());
        return transaction(() -> {
            String login = in.login();
            User user = findUser(login);
            ApiException invalid = new ApiException(401, "Invalid username/email or password.");

            if (user == null) {
                verifyPassword(in.password(), DUMMY_HASH);
                logEvent("login_failed", null, login, false, "unknown account");
                throw CommitThenRaise.of(invalid);
            }

            LocalDateTime now = Py.utcnow();
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                long minutes = Duration.between(now, user.getLockedUntil()).getSeconds() / 60 + 1;
                logEvent("login_blocked", user, "", false, "account locked");
                throw new CommitThenRaise(423, "Too many failed attempts. This account is locked for " + minutes + " more minute(s).");
            }

            if (!verifyPassword(in.password(), user.getPasswordHash())) {
                user.setFailedLoginCount(user.getFailedLoginCount() + 1);
                String detail = "wrong password (" + user.getFailedLoginCount() + "/" + live.maxFailedLogins() + ")";
                if (user.getFailedLoginCount() >= live.maxFailedLogins()) {
                    user.setLockedUntil(now.plusMinutes(live.lockoutMinutes()));
                    user.setFailedLoginCount(0);
                    detail = "wrong password, account locked";
                }
                logEvent("login_failed", user, "", false, detail);
                if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                    throw new CommitThenRaise(423, "Too many failed attempts. This account is locked for " + live.lockoutMinutes() + " minutes.");
                }
                int left = live.maxFailedLogins() - user.getFailedLoginCount();
                throw new CommitThenRaise(401, left <= 2 ? "Invalid username/email or password. " + left
                        + " attempt(s) left before the account is locked." : invalid.text());
            }

            if (!Boolean.TRUE.equals(user.getIsActive())) {
                logEvent("login_blocked", user, "", false, "account disabled");
                throw new CommitThenRaise(403, "This account has been disabled. Contact your system administrator.");
            }

            // Password is right.
            user.setFailedLoginCount(0);

            // "Keep me signed in" on a browser that already passed the emailed code: no code needed
            TrustedDevice device = in.rememberMe() ? trustedDevice(user) : null;
            if (device != null) {
                device.setLastUsedAt(now);
                user.setLockedUntil(null);
                user.setLastLoginAt(now);
                startSession(user, true, null, response);
                logEvent("login_success", user, "remember me; trusted device, no code");
                return tokenResponse(user);
            }
            if (!in.rememberMe()) forgetDevice(response);  // box unticked: this browser needs the code again

            // The built-in admin before an email address is set up: the password is enough.
            if (isBuiltInAdmin(user) && Py.isEmpty(user.getEmail())) {
                user.setLockedUntil(null);
                user.setLastLoginAt(now);
                startSession(user, in.rememberMe(), null, response);
                logEvent("login_success", user, "built-in admin, no email address: password only");
                return tokenResponse(user);
            }

            // Step 2: a code emailed to the account's address.
            if (Py.isEmpty(user.getEmail())) {
                throw new CommitThenRaise(403, "This account has no email address, so a sign-in code cannot be sent. Ask another user to add one.");
            }
            try {
                emailCheck.validateRealEmail(user.getEmail());
            } catch (ApiException e) {
                if (e.status() == 400) {
                    throw new CommitThenRaise(403, "The email on this account (" + user.getEmail() + ") cannot receive mail, so a "
                            + "sign-in code cannot be sent. Ask another user to correct it.");
                }
                throw e;
            }
            return sendLoginCode(user);
        });
    }

    /** POST /api/auth/refresh: exchange the refresh cookie for a new access token. */
    public Map<String, Object> refresh(HttpServletResponse response) {
        return transaction(() -> {
            RefreshToken token = currentRefreshToken();
            if (token == null) {
                clearRefreshCookie(response);
                throw CurrentUser.unauthorized("Please sign in.");
            }
            LocalDateTime now = Py.utcnow();
            User user = users.findById(token.getUserId()).orElse(null);

            if (token.getRevokedAt() != null) {
                if (token.getReplacedById() != null && Duration.between(token.getRevokedAt(), now).getSeconds() <= REFRESH_REUSE_GRACE_SECONDS) {
                    // Another tab refreshed a moment ago and the browser already has the new cookie
                    if (Boolean.TRUE.equals(user.getIsActive())) return tokenResponse(user);
                }
                if (token.getReplacedById() != null) {
                    // An old, already-rotated token was used again: treat the session as stolen
                    jdbc.sql("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL")
                            .params(now, token.getFamilyId()).update();
                    logEvent("refresh_reuse", user, "", false, "old refresh token re-used; session revoked");
                    clearRefreshCookie(response);
                    throw CommitThenRaise.of(CurrentUser.unauthorized("Your session has ended. Please sign in again."));
                }
                clearRefreshCookie(response);
                throw CurrentUser.unauthorized("Your session has ended. Please sign in again.");
            }

            if (!token.getExpiresAt().isAfter(now) || !Boolean.TRUE.equals(user.getIsActive())) {
                token.setRevokedAt(now);
                clearRefreshCookie(response);
                throw CommitThenRaise.of(CurrentUser.unauthorized("Your session has expired. Please sign in again."));
            }

            RefreshToken newToken = startSession(user, Boolean.TRUE.equals(token.getRememberMe()), token.getFamilyId(), response);
            newToken.setCreatedAt(token.getCreatedAt());  // the session started when the user signed in
            token.setRevokedAt(now);
            token.setReplacedById(newToken.getId());
            return tokenResponse(user);
        });
    }

    /** POST /api/auth/logout */
    public Map<String, Object> logout(HttpServletResponse response) {
        RefreshToken token = currentRefreshToken();
        if (token != null) {
            transaction(() -> {
                jdbc.sql("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL")
                        .params(Py.utcnow(), token.getFamilyId()).update();
                logEvent("logout", users.findById(token.getUserId()).orElse(null), "");
                return null;
            });
        }
        clearRefreshCookie(response);
        return Py.map("message", "Signed out.");
    }

    /** POST /api/auth/logout-all: sign out on every device. */
    public Map<String, Object> logoutAll(User user, HttpServletResponse response) {
        transaction(() -> {
            revokeAllSessions(user);
            revokeTrustedDevices(user);
            user.setTokenVersion(user.getTokenVersion() + 1);
            logEvent("logout_all", user, "");
            return null;
        });
        clearRefreshCookie(response);
        return Py.map("message", "Signed out on all devices.");
    }

    /** PUT /api/auth/me: name changes apply at once; a new email only replaces the old one after its code is entered. */
    public Map<String, Object> updateProfile(User user, ProfileUpdateRequest in) {
        return transaction(() -> {
            String fullName = in.fullName(), email = in.email();
            if (!fullName.equals(user.getFullName())) {
                user.setFullName(fullName);
                logEvent("profile_updated", user, "changed name");
            }
            String message = "Profile saved.";
            if (!email.equals(user.getEmail()) && !email.equals(user.getPendingEmail())) {
                if (accountConflict(null, email, user.getId()) != null) throw new ApiException(409, "Another account already uses this email.");
                emailCheck.validateRealEmail(email);
                user.setPendingEmail(email);
                sendVerificationCode(user, email, "change", false);
                logEvent("email_change_requested", user, email);
                message = "We've sent a 6-digit code to " + email + ". Enter it to confirm the new address.";
            } else if (email.equals(user.getEmail()) && !Py.isEmpty(user.getPendingEmail())) {
                user.setPendingEmail(null);  // typed the old address back: cancel the change
            }
            Map<String, Object> out = userToDict(user);
            out.put("message", message);
            return out;
        });
    }

    /** POST /api/auth/me/confirm-email */
    public Map<String, Object> confirmEmailChange(User user, EmailCodeRequest in) {
        return transaction(() -> {
            if (Py.isEmpty(user.getPendingEmail())) throw new ApiException(400, "There is no email change waiting for confirmation.");
            checkCode(user, "change", user.getPendingEmail(), in.code());
            if (accountConflict(null, user.getPendingEmail(), user.getId()) != null) {
                throw new ApiException(409, "Another account started using this email in the meantime.");
            }
            purgeAbandoned(null, user.getPendingEmail());
            String old = user.getEmail();
            user.setEmail(user.getPendingEmail());
            user.setPendingEmail(null);
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(Py.utcnow());
            logEvent("email_changed", user, old + " -> " + user.getEmail());
            Map<String, Object> out = userToDict(user);
            out.put("message", "Your email address has been changed.");
            return out;
        });
    }

    /** POST /api/auth/me/resend-email-code */
    public Map<String, Object> resendEmailChangeCode(User user) {
        return transaction(() -> {
            if (Py.isEmpty(user.getPendingEmail())) throw new ApiException(400, "There is no email change waiting for confirmation.");
            if (!sendVerificationCode(user, user.getPendingEmail(), "change", true)) {
                throw new ApiException(429, "Please wait " + NpsbProperties.VERIFICATION_RESEND_SECONDS + " seconds before asking for another code.");
            }
            return Py.map("message", "A new code has been sent to " + user.getPendingEmail() + ".");
        });
    }

    /** DELETE /api/auth/me/pending-email */
    public Map<String, Object> cancelEmailChange(User user) {
        return transaction(() -> {
            user.setPendingEmail(null);
            jdbc.sql("UPDATE email_verification_codes SET used_at = ? WHERE user_id = ? AND purpose = 'change' AND used_at IS NULL")
                    .params(Py.utcnow(), user.getId()).update();
            return userToDict(user);
        });
    }

    /** POST /api/auth/change-password */
    public Map<String, Object> changePassword(User user, ChangePasswordRequest in, HttpServletResponse response) {
        return transaction(() -> {
            String current = in.currentPassword(), newPassword = in.newPassword();
            if (!verifyPassword(current, user.getPasswordHash())) {
                logEvent("password_change_failed", user, "", false, "wrong current password");
                throw new CommitThenRaise(400, "Current password is incorrect.");
            }
            if (newPassword.equals(current)) throw new ApiException(400, "The new password must be different from the current one.");
            checkPasswordStrength(newPassword, user.getUsername(), Py.or(user.getEmail(), ""));

            RefreshToken token = currentRefreshToken();
            boolean remember = token != null && Boolean.TRUE.equals(token.getRememberMe());
            user.setPasswordHash(hashPassword(newPassword));
            user.setPasswordChangedAt(Py.utcnow());
            user.setTokenVersion(user.getTokenVersion() + 1);  // old access tokens everywhere stop working
            revokeAllSessions(user);                            // sign out other devices...
            revokeTrustedDevices(user);                         // ...and every browser needs the emailed code again
            startSession(user, remember, null, response);       // ...but keep this one signed in
            logEvent("password_changed", user, "other sessions signed out");
            return tokenResponse(user);
        });
    }

    /** A reset code to email once the request has been answered. */
    private record CodeEmail(String to, String name, String code) {}

    /**
     * POST /api/auth/forgot-password, step 1: email a 6-digit code (OTP) to the account. Always the same answer, so
     * nobody can find out which emails have an account. Only the newest code works; a new one can be asked for after a minute.
     */
    public Map<String, Object> forgotPassword(ForgotPasswordRequest in) {
        String email = in.email();
        forgotLimiter.check(ClientInfo.clientIp());
        Map<String, Object> answer = Py.map("message", "If an account exists for " + email + ", we've emailed it a 6-digit code. "
                + "It is valid for " + live.verificationCodeMinutes() + " minutes.");
        CodeEmail mail = transaction(() -> {
            User user = users.findByEmail(email).orElse(null);
            if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
                logEvent("password_reset_requested", null, email, false, "no active account");
                return null;
            }
            EmailVerificationCode previous = latestCode(user, "reset", user.getEmail());
            if (previous != null && Duration.between(previous.getCreatedAt(), Py.utcnow()).getSeconds() < NpsbProperties.VERIFICATION_RESEND_SECONDS) {
                return null;  // a code was sent less than a minute ago: it still works
            }
            jdbc.sql("UPDATE email_verification_codes SET used_at = ? WHERE user_id = ? AND purpose = 'reset' AND used_at IS NULL")
                    .params(Py.utcnow(), user.getId()).update();
            String code = Crypto.sixDigits();
            EmailVerificationCode record = new EmailVerificationCode();
            record.setUserId(user.getId());
            record.setEmail(user.getEmail());
            record.setPurpose("reset");
            record.setCodeHash(codeHash(user, "reset", code));
            record.setExpiresAt(Py.utcnow().plusMinutes(live.verificationCodeMinutes()));
            codes.save(record);
            logEvent("password_reset_requested", user, "code emailed");
            return new CodeEmail(user.getEmail(), Py.or(user.getFullName(), user.getUsername()), code);
        });
        if (mail != null) {
            backgroundTasks.add(() -> {
                try {
                    deliverCodeEmail(mail.to(), mail.name(), mail.code(), "reset");
                } catch (Exception exc) {  // never crash the request; the log shows what happened
                    LOG.warn("Password reset code to " + mail.to() + " could not be sent: " + exc.getMessage());
                }
            });
        }
        return answer;
    }

    /** POST /api/auth/reset-password, step 2: the emailed code is right -> the new password is set and every session is signed out. */
    public Map<String, Object> resetPassword(ResetPasswordRequest in) {
        return transaction(() -> {
            User user = users.findByEmail(in.email()).orElse(null);
            if (user == null || !Boolean.TRUE.equals(user.getIsActive())) throw new ApiException(400, EXPIRED_CODE);
            checkPasswordStrength(in.newPassword(), user.getUsername(), Py.or(user.getEmail(), ""));  // before the code, so no attempt is used up
            checkCode(user, "reset", user.getEmail(), in.code());

            user.setPasswordHash(hashPassword(in.newPassword()));
            user.setPasswordChangedAt(Py.utcnow());
            user.setTokenVersion(user.getTokenVersion() + 1);
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            if (!Boolean.TRUE.equals(user.getEmailVerified())) {  // the code reached this inbox, so the address is real
                user.setEmailVerified(true);
                user.setEmailVerifiedAt(Py.utcnow());
            }
            revokeAllSessions(user);
            revokeTrustedDevices(user);
            logEvent("password_reset", user, "with the emailed code; all sessions signed out");
            return Py.map("message", "Your password has been reset. Sign in with the new password.");
        });
    }

    /** GET /api/auth/sessions: devices/browsers where the user is signed in. */
    public List<Object> listSessions(User user) {
        RefreshToken current = currentRefreshToken();
        List<Object> out = new ArrayList<>();
        for (RefreshToken t : refreshTokens.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastUsedAtDesc(user.getId(), Py.utcnow())) {
            out.add(Py.map(
                    "id", t.getId(),
                    "user_agent", t.getUserAgent(),
                    "ip_address", t.getIpAddress(),
                    "signed_in_at", Py.isoUtc(t.getCreatedAt()),
                    "last_used_at", Py.isoUtc(t.getLastUsedAt()),
                    "expires_at", Py.isoUtc(t.getExpiresAt()),
                    "remember_me", t.getRememberMe(),
                    "current", current != null && current.getFamilyId().equals(t.getFamilyId())));
        }
        return out;
    }

    /** DELETE /api/auth/sessions/{session_id} */
    public Map<String, Object> revokeSession(User user, int sessionId) {
        return transaction(() -> {
            RefreshToken token = refreshTokens.findById(sessionId).orElse(null);
            if (token == null || !token.getUserId().equals(user.getId())) throw new ApiException(404, "Session not found.");
            jdbc.sql("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL")
                    .params(Py.utcnow(), token.getFamilyId()).update();
            logEvent("session_revoked", user, Py.cut(token.getUserAgent(), 120));
            return Py.map("message", "Session signed out.");
        });
    }

    /** GET /api/auth/activity */
    public List<Object> myActivity(User user) {
        List<Object> out = new ArrayList<>();
        for (AuthEvent e : authEvents.findTop25ByUserIdOrderByCreatedAtDesc(user.getId())) out.add(eventToDict(e));
        return out;
    }

    public static Map<String, Object> eventToDict(AuthEvent e) {
        return Py.map("id", e.getId(), "event", e.getEvent(), "success", e.getSuccess(), "detail", e.getDetail(),
                "username", e.getUsername(), "ip_address", e.getIpAddress(), "user_agent", e.getUserAgent(),
                "created_at", Py.isoUtc(e.getCreatedAt()));
    }
}
