package com.nagad.npsb.api;

import com.nagad.npsb.Config;
import com.nagad.npsb.Crypto;
import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.AuthEvent;
import com.nagad.npsb.models.EmailVerificationCode;
import com.nagad.npsb.models.PasswordResetToken;
import com.nagad.npsb.models.PendingRegistration;
import com.nagad.npsb.models.RefreshToken;
import com.nagad.npsb.models.TrustedDevice;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.EmailCheck;
import com.nagad.npsb.service.EmailService;
import com.nagad.npsb.web.ApiError;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.Req;
import com.nagad.npsb.web.Server;
import com.nagad.npsb.web.ValueError;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
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
 * Two roles: "admin" and "user". Creating an admin account needs ADMIN_SIGNUP_CODE. Only admins manage users,
 * change partners and send emails (requireAdmin); users can do everything else.
 *
 * Protection: PBKDF2-SHA256 passwords (600k iterations), lock-out after wrong passwords, rate limits per IP,
 * tokens stored only as SHA-256 hashes, and every sign-in, failure, sign-out and change in auth_events.
 */
public final class Auth {
    private Auth() {}

    static final int PBKDF2_ITERATIONS = 600_000;
    static final String REFRESH_COOKIE = "npsb_refresh";
    static final String REFRESH_COOKIE_PATH = "/api/auth";
    static final String TRUSTED_COOKIE = "npsb_trusted";  // trusted device: password-only sign-in (same path as the refresh cookie)
    static final int REFRESH_REUSE_GRACE_SECONDS = 30;    // two tabs refreshing at the same moment is not theft

    private static final Pattern USERNAME_RE = Pattern.compile("[a-z0-9._-]{3,30}");
    private static final Pattern EMAIL_RE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "12345678", "123456789", "1234567890",
            "qwerty123", "qwertyuiop", "11111111", "iloveyou", "admin123", "welcome1",
            "letmein1", "abc12345", "p@ssw0rd", "passw0rd");

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
        String lowered = password.toLowerCase(java.util.Locale.ROOT);
        if (username != null && username.length() >= 3 && lowered.contains(username.toLowerCase(java.util.Locale.ROOT))) {
            problems.add("must not contain the username");
        }
        String localPart = email != null && !email.isEmpty() ? email.split("@", -1)[0].toLowerCase(java.util.Locale.ROOT) : "";
        if (localPart.length() >= 3 && lowered.contains(localPart)) problems.add("must not contain the email address");
        if (COMMON_PASSWORDS.contains(lowered)) problems.add("must not be a common password");
        return problems;
    }

    public static void checkPasswordStrength(String password, String username, String email) {
        List<String> problems = passwordProblems(password, username, email);
        if (!problems.isEmpty()) throw new ApiError(400, "Password is too weak: " + String.join(", ", problems) + ".");
    }

    public static String hashToken(String token) {
        return Crypto.sha256Hex(token);
    }

    public static void logEvent(Db db, Req req, String event, User user, String username, boolean success, String detail) {
        AuthEvent e = new AuthEvent();
        e.userId = user != null ? user.id : null;
        e.username = Py.cut(user != null ? user.username : username == null ? "" : username, 254);
        e.event = event;
        e.success = success;
        e.detail = Py.cut(detail == null ? "" : detail, 255);
        e.ipAddress = req == null ? "" : req.clientIp();
        e.userAgent = req == null ? "" : req.userAgent();
        db.add(e);
    }

    public static void logEvent(Db db, Req req, String event, User user, String detail) {
        logEvent(db, req, event, user, "", true, detail);
    }

    public static Map<String, Object> userToDict(User user) {
        LocalDateTime now = Py.utcnow();
        boolean locked = user.lockedUntil != null && user.lockedUntil.isAfter(now);
        return Py.map(
                "id", user.id,
                "username", user.username,
                "email", user.email,
                "full_name", user.fullName,
                "role", user.role,
                "email_verified", user.emailVerified,
                "pending_email", user.pendingEmail,
                "is_active", user.isActive,
                "is_locked", locked,
                "locked_until", locked ? Py.isoUtc(user.lockedUntil) : null,
                "last_login_at", Py.isoUtc(user.lastLoginAt),
                "password_changed_at", Py.isoUtc(user.passwordChangedAt),
                "created_at", Py.isoUtc(user.createdAt));
    }

    // ---------------------------------------------------------------- rate limiting

    /** At most `limit` requests per `window` seconds for one key (in memory, per server process). */
    static final class RateLimiter {
        private final IntSupplier limit;
        private final int window;
        private final Map<String, Deque<Long>> hits = new HashMap<>();

        RateLimiter(IntSupplier limit, int window) {
            this.limit = limit;
            this.window = window;
        }

        synchronized void check(String key) {
            long now = System.nanoTime();
            long windowNanos = window * 1_000_000_000L;
            Deque<Long> h = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!h.isEmpty() && h.peekFirst() <= now - windowNanos) h.pollFirst();
            if (h.size() >= limit.getAsInt()) {
                long retry = (long) (window - (now - h.peekFirst()) / 1e9) + 1;
                throw new ApiError(429, "Too many attempts. Please wait " + retry + " seconds and try again.")
                        .header("Retry-After", String.valueOf(retry));
            }
            h.addLast(now);
        }
    }

    static final RateLimiter LOGIN_LIMITER = new RateLimiter(Config::LOGIN_RATE_LIMIT_PER_MINUTE, 60);  // System Settings can change it
    static final RateLimiter REGISTER_LIMITER = new RateLimiter(() -> 5, 60);
    static final RateLimiter FORGOT_LIMITER = new RateLimiter(() -> 5, 60);
    static final RateLimiter VERIFY_LIMITER = new RateLimiter(() -> 10, 60);
    static final int MAX_LOGIN_CODES = 5;  // codes per sign-in within VERIFICATION_CODE_MINUTES, counting resends
    static final RateLimiter RESEND_LIMITER = new RateLimiter(() -> 5, 60);

    // ---------------------------------------------------------------- tokens

    static String createAccessToken(User user) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> payload = Py.map(
                "sub", String.valueOf(user.id),
                "username", user.username,
                "ver", user.tokenVersion,
                "type", "access",
                "iss", Config.JWT_ISSUER,
                "iat", now,
                "exp", now + Config.ACCESS_TOKEN_MINUTES() * 60L,
                "jti", Crypto.tokenHex(8));
        return Crypto.jwtEncode(payload, Config.JWT_SECRET);
    }

    /** Create a refresh token, store its hash, and put the raw token in the httpOnly cookie. */
    static RefreshToken startSession(Db db, Req req, User user, boolean rememberMe, String familyId) {
        String raw = Crypto.tokenUrlsafe(48);
        int days = rememberMe ? Config.REFRESH_TOKEN_REMEMBER_DAYS() : Config.REFRESH_TOKEN_DAYS();
        RefreshToken token = new RefreshToken();
        token.userId = user.id;
        token.tokenHash = hashToken(raw);
        token.familyId = familyId != null ? familyId : Crypto.tokenHex(16);
        token.rememberMe = rememberMe;
        token.userAgent = req.userAgent();
        token.ipAddress = req.clientIp();
        token.expiresAt = Py.utcnow().plusDays(days);
        db.add(token);
        db.flush();
        // without "remember me": gone when the browser closes
        req.res.setCookie(REFRESH_COOKIE, raw, rememberMe ? days * 86400 : null, true, Config.COOKIE_SECURE, "strict", REFRESH_COOKIE_PATH);
        return token;
    }

    static void clearRefreshCookie(Req req) {
        req.res.deleteCookie(REFRESH_COOKIE, REFRESH_COOKIE_PATH, true, Config.COOKIE_SECURE, "strict");
    }

    public static void revokeAllSessions(Db db, User user, String exceptFamily) {
        if (exceptFamily != null) {
            db.execute("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL AND family_id != ?",
                    Py.utcnow(), user.id, exceptFamily);
        } else {
            db.execute("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", Py.utcnow(), user.id);
        }
    }

    /** Remember this browser: for the next REFRESH_TOKEN_REMEMBER_DAYS it signs in without a code. */
    static void trustDevice(Db db, Req req, User user) {
        String raw = Crypto.tokenUrlsafe(48);
        int days = Config.REFRESH_TOKEN_REMEMBER_DAYS();
        TrustedDevice d = new TrustedDevice();
        d.userId = user.id;
        d.tokenHash = hashToken(raw);
        d.userAgent = req.userAgent();
        d.ipAddress = req.clientIp();
        d.expiresAt = Py.utcnow().plusDays(days);
        db.add(d);
        req.res.setCookie(TRUSTED_COOKIE, raw, days * 86400, true, Config.COOKIE_SECURE, "strict", REFRESH_COOKIE_PATH);
    }

    /** This browser's trusted-device record for this user, if it is still valid. */
    static TrustedDevice trustedDevice(Db db, Req req, User user) {
        String raw = req.cookie(TRUSTED_COOKIE);
        if (Py.isEmpty(raw)) return null;
        return db.first(TrustedDevice.class, "WHERE t.token_hash = ? AND t.user_id = ? AND t.revoked_at IS NULL AND t.expires_at > ?",
                hashToken(raw), user.id, Py.utcnow());
    }

    /** Stop trusting this browser (it needs the emailed code again). */
    static void forgetDevice(Db db, Req req) {
        String raw = req.cookie(TRUSTED_COOKIE);
        if (!Py.isEmpty(raw)) {
            db.execute("UPDATE trusted_devices SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", Py.utcnow(), hashToken(raw));
            req.res.deleteCookie(TRUSTED_COOKIE, REFRESH_COOKIE_PATH, true, Config.COOKIE_SECURE, "strict");
        }
    }

    /** Every browser of this user needs the emailed code again. */
    public static void revokeTrustedDevices(Db db, User user) {
        db.execute("UPDATE trusted_devices SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL", Py.utcnow(), user.id);
    }

    static RefreshToken currentRefreshToken(Db db, Req req) {
        String raw = req.cookie(REFRESH_COOKIE);
        if (Py.isEmpty(raw)) return null;
        return db.first(RefreshToken.class, "WHERE t.token_hash = ?", hashToken(raw));
    }

    static Map<String, Object> tokenResponse(User user) {
        return Py.map(
                "access_token", createAccessToken(user),
                "token_type", "bearer",
                "expires_in", Config.ACCESS_TOKEN_MINUTES() * 60,
                "user", userToDict(user));
    }

    // ---------------------------------------------------------------- dependencies

    static ApiError unauthorized(String message) {
        return new ApiError(401, message).header("WWW-Authenticate", "Bearer");
    }

    /** The signed-in user, or 401. Used by every protected endpoint. */
    public static User currentUser(Req req) {
        if (req.user != null) return req.user;
        String header = req.header("Authorization");
        String token = null;
        if (header != null) {
            String[] parts = header.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("bearer")) token = parts[1].strip();
        }
        if (token == null || token.isEmpty()) throw unauthorized("Please sign in.");
        Map<String, Object> payload;
        try {
            payload = Crypto.jwtDecode(token, Config.JWT_SECRET, Config.JWT_ISSUER, "exp", "sub", "ver");
        } catch (Crypto.JwtExpired e) {
            throw unauthorized("Your session has expired. Please sign in again.");
        } catch (Crypto.JwtError e) {
            throw unauthorized("Invalid session. Please sign in again.");
        }
        if (!"access".equals(payload.get("type"))) throw unauthorized("Invalid session. Please sign in again.");
        int id;
        try {
            id = Integer.parseInt(Py.strip((String) payload.get("sub")));
        } catch (NumberFormatException e) {
            throw unauthorized("Invalid session. Please sign in again.");
        }
        User user = req.db().get(User.class, id);
        Object ver = payload.get("ver");
        boolean sameVersion = ver instanceof Number n && !(ver instanceof Double d && d != Math.rint(d))
                && n.longValue() == user_version(user);
        if (user == null || !Boolean.TRUE.equals(user.isActive) || !sameVersion) {
            throw unauthorized("Your session has ended. Please sign in again.");
        }
        req.user = user;
        return user;
    }

    private static long user_version(User user) {
        return user == null || user.tokenVersion == null ? Long.MIN_VALUE : user.tokenVersion;
    }

    /** The signed-in user if they are an admin, otherwise 403. */
    public static User requireAdmin(Req req) {
        User user = currentUser(req);
        if (!"admin".equals(user.role)) throw new ApiError(403, "Only admins can do this.");
        return user;
    }

    /** Admin accounts can only be created with the right admin code. */
    static void checkAdminCode(Db db, Req req, String role, String code, String username) {
        if ("admin".equals(role) && !Crypto.equal(Py.strip(code), Config.ADMIN_SIGNUP_CODE())) {
            logEvent(db, req, "admin_code_failed", null, username, false, "wrong admin code for a new admin account");
            db.commit();
            throw new ApiError(403, "Incorrect admin code. Leave the role as User, or ask an admin for the code.");
        }
    }

    // ---------------------------------------------------------------- email verification codes

    static EmailVerificationCode latestCode(Db db, User user, String purpose, String email) {
        return db.first(EmailVerificationCode.class, "WHERE t.user_id = ? AND t.purpose = ? AND t.email = ? AND t.used_at IS NULL ORDER BY t.id DESC",
                user.id, purpose, email);
    }

    public static String codeHash(User user, String purpose, String code) {
        return hashToken(user.id + ":" + purpose + ":" + code);
    }

    /**
     * Email a fresh 6-digit code (older codes stop working). Returns false if one was sent less than
     * VERIFICATION_RESEND_SECONDS ago. 502 if the email cannot be sent.
     */
    static boolean sendVerificationCode(Db db, User user, String email, String purpose, boolean respectCooldown) {
        EmailVerificationCode previous = latestCode(db, user, purpose, email);
        if (respectCooldown && previous != null
                && Duration.between(previous.createdAt, Py.utcnow()).getSeconds() < Config.VERIFICATION_RESEND_SECONDS) {
            return false;
        }
        db.execute("UPDATE email_verification_codes SET used_at = ? WHERE user_id = ? AND purpose = ? AND used_at IS NULL",
                Py.utcnow(), user.id, purpose);
        String code = Crypto.sixDigits();
        EmailVerificationCode record = new EmailVerificationCode();
        record.userId = user.id;
        record.email = email;
        record.purpose = purpose;
        record.codeHash = codeHash(user, purpose, code);
        record.expiresAt = Py.utcnow().plusMinutes(Config.VERIFICATION_CODE_MINUTES());
        db.add(record);
        db.flush();
        try {
            deliverCodeEmail(email, Py.or(user.fullName, user.username), code, purpose);
        } catch (Exception exc) {
            throw new ApiError(502, "We could not send a verification email to " + email + ". Check that the "
                    + "address is correct and try again. (" + exc.getMessage() + ")");
        }
        return true;
    }

    static void deliverCodeEmail(String email, String name, String code, String purpose) {
        String action = "change".equals(purpose) ? "confirm your new email address" : "sign in";
        String body = "\n    <div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#1f2430;max-width:520px\">\n"
                + "      <p>Hello " + Py.escape(name) + ",</p>\n"
                + "      <p>Use this code to " + action + " for NPSB Dispute Email Automation:</p>\n"
                + "      <p style=\"font-size:30px;font-weight:700;letter-spacing:8px;margin:20px 0;color:#e8222d\">" + code + "</p>\n"
                + "      <p>The code is valid for " + Config.VERIFICATION_CODE_MINUTES() + " minutes. If you did not request it,\n"
                + "         you can ignore this email.</p>\n"
                + "    </div>";
        String subject = "login".equals(purpose) ? "Your sign-in code" : "Your verification code";
        EmailService.sendEmail(List.of(email), List.of(), subject + ": " + code, body, null, null, null);
    }

    /** 400 unless `code` is the current, unexpired code for this address. */
    static void checkCode(Db db, User user, String purpose, String email, String code) {
        EmailVerificationCode record = latestCode(db, user, purpose, email);
        if (record == null || !record.expiresAt.isAfter(Py.utcnow()) || record.attempts >= Config.VERIFICATION_MAX_ATTEMPTS) {
            throw new ApiError(400, "This code has expired or was replaced. Request a new code.");
        }
        if (!Crypto.equal(record.codeHash, codeHash(user, purpose, Py.strip(code)))) {
            record.attempts += 1;
            int left = Config.VERIFICATION_MAX_ATTEMPTS - record.attempts;
            db.commit();
            if (left <= 0) throw new ApiError(400, "Too many wrong codes. Request a new code.");
            throw new ApiError(400, "Incorrect code. " + left + " attempt(s) left.");
        }
        record.usedAt = Py.utcnow();
    }

    /** A one-time code made on the server (recover code USERNAME) for when email is broken. */
    static EmailVerificationCode validRecoveryCode(Db db, User user) {
        EmailVerificationCode record = latestCode(db, user, "recovery", Py.or(user.email, ""));
        if (record != null && record.expiresAt.isAfter(Py.utcnow()) && record.attempts < Config.VERIFICATION_MAX_ATTEMPTS) return record;
        return null;
    }

    /**
     * Password OK (or account just created): email the code and tell the page to ask for it. With a recovery
     * code waiting, nothing is emailed: the code from the server is asked for instead.
     */
    static Map<String, Object> sendLoginCode(Db db, Req req, User user) {
        if (validRecoveryCode(db, user) != null) {
            logEvent(db, req, "login_code_sent", user, "recovery code from the server, no email");
            return Py.map("otp_required", true, "login", user.username, "email", user.email,
                    "message", "Enter the one-time recovery code created on the server.");
        }
        boolean sent = sendVerificationCode(db, user, user.email, "login", true);
        logEvent(db, req, "login_code_sent", user, sent ? "" : "recent code still valid");
        String note = sent ? "We've sent a 6-digit code to" : "Enter the 6-digit code we sent to";
        return Py.map("otp_required", true, "login", user.username, "email", user.email, "message", note + " " + user.email + ".");
    }

    static User findUser(Db db, String login) {
        return db.first(User.class, login.contains("@") ? "WHERE t.email = ?" : "WHERE t.username = ?", login);
    }

    // ---------------------------------------------------------------- accounts only exist once created
    // A self sign-up only becomes an account when its emailed code is entered (pending_registrations until then).
    // Accounts made by the old sign-up and never confirmed or used are "abandoned": they don't count as taking a
    // username or an email, and are removed when a real account needs the name or address.

    static boolean isAbandoned(Db db, User user) {
        if (Boolean.TRUE.equals(user.emailVerified) || user.lastLoginAt != null) return false;
        return db.scalar("SELECT id FROM auth_events WHERE user_id = ? AND event = 'register' LIMIT 1", user.id) != null;
    }

    /** Why this username / email can't be used (a real account has it), or null. */
    public static String accountConflict(Db db, String username, String email, Integer exceptId) {
        String[][] checks = {{"username", username, "This username is already taken."}, {"email", email, "An account with this email already exists."}};
        for (String[] check : checks) {
            if (Py.isEmpty(check[1])) continue;
            for (User other : db.all(User.class, "WHERE t." + check[0] + " = ? AND t.id != ?", check[1], exceptId == null ? 0 : exceptId)) {
                if (!isAbandoned(db, other)) return check[2];
            }
        }
        return null;
    }

    /** Remove abandoned sign-ups holding this username / email, so a real account can use it. */
    public static void purgeAbandoned(Db db, String username, String email) {
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!Py.isEmpty(username)) { conditions.add("t.username = ?"); args.add(username); }
        if (!Py.isEmpty(email)) { conditions.add("t.email = ?"); args.add(email); }
        if (conditions.isEmpty()) return;
        for (User user : db.all(User.class, "WHERE " + String.join(" OR ", conditions), args.toArray())) {
            if (!isAbandoned(db, user)) continue;
            for (String table : new String[]{"email_verification_codes", "refresh_tokens", "trusted_devices", "password_reset_tokens"}) {
                db.execute("DELETE FROM " + table + " WHERE user_id = ?", user.id);
            }
            db.execute("UPDATE auth_events SET user_id = NULL WHERE user_id = ?", user.id);
            db.delete(user);
        }
        db.flush();  // before the new account is inserted (the names are unique)
    }

    static long realUserCount(Db db) {
        long n = 0;
        for (User u : db.all(User.class, "")) if (!isAbandoned(db, u)) n++;
        return n;
    }

    static String registrationCodeHash(PendingRegistration pending, String code) {
        return hashToken("registration:" + pending.id + ":" + code);
    }

    /** The newest unfinished sign-up for this username or email that has not expired. */
    static PendingRegistration pendingRegistration(Db db, String login) {
        return db.first(PendingRegistration.class, (login.contains("@") ? "WHERE t.email = ?" : "WHERE t.username = ?")
                + " AND t.expires_at > ? ORDER BY t.id DESC", login, Py.utcnow());
    }

    // ================================================================ request bodies

    public static String cleanFullName(String v) {
        v = Py.squash(v);
        if (v.length() < 2 || v.length() > 100) throw new ValueError("Full name must be 2-100 characters");
        return v;
    }

    public static String cleanUsername(String v) {
        v = Py.strip(v).toLowerCase(java.util.Locale.ROOT);
        if (!USERNAME_RE.matcher(v).matches()) {
            throw new ValueError("Username must be 3-30 characters: letters, numbers, dot, dash or underscore");
        }
        return v;
    }

    public static String cleanEmail(String v) {
        v = Py.strip(v).toLowerCase(java.util.Locale.ROOT);
        if (v.length() > 254 || !EMAIL_RE.matcher(v).matches()) throw new ValueError("Enter a valid email address");
        return v;
    }

    static String normalizeLogin(String v) {
        return Py.strip(v).toLowerCase(java.util.Locale.ROOT);
    }

    // ================================================================ endpoints

    /** The first account can always be created; after that it depends on ALLOW_REGISTRATION. */
    static boolean registrationOpen(Db db) {
        return Config.ALLOW_REGISTRATION() || realUserCount(db) == 0;
    }

    public static void routes(Server s) {
        s.get("/api/auth/settings", "Auth Settings", req -> {
            Db db = req.db();
            return Py.map("allow_registration", registrationOpen(db), "first_user", realUserCount(db) == 0,
                    "remember_me_days", Config.REFRESH_TOKEN_REMEMBER_DAYS());
        });
        s.post("/api/auth/register", 201, "Register", Auth::register);
        s.post("/api/auth/verify-code", "Step 2 of sign-in: the emailed code is correct -> the user is signed in.", Auth::verifyCode);
        s.post("/api/auth/resend-code", "A new sign-in code (only while a sign-in is in progress).", Auth::resendCode);
        s.post("/api/auth/login", "Login", Auth::login);
        s.post("/api/auth/refresh", "Exchange the refresh cookie for a new access token.", Auth::refresh);
        s.post("/api/auth/logout", "Logout", Auth::logout);
        s.post("/api/auth/logout-all", "Sign out on every device.", Auth::logoutAll);
        s.get("/api/auth/me", "Me", req -> userToDict(currentUser(req)));
        s.put("/api/auth/me", "Name changes apply at once. A new email only replaces the old one after its code is entered.",
                Auth::updateProfile);
        s.post("/api/auth/me/confirm-email", "Confirm Email Change", Auth::confirmEmailChange);
        s.post("/api/auth/me/resend-email-code", "Resend Email Change Code", Auth::resendEmailChangeCode);
        s.delete("/api/auth/me/pending-email", "Cancel Email Change", Auth::cancelEmailChange);
        s.post("/api/auth/change-password", "Change Password", Auth::changePassword);
        s.post("/api/auth/forgot-password", "Always gives the same answer, so nobody can find out which emails have an account.",
                Auth::forgotPassword);
        s.get("/api/auth/reset-password/{token}", "Lets the reset page say early whether the link is still valid.", Auth::checkResetToken);
        s.post("/api/auth/reset-password", "Reset Password", Auth::resetPassword);
        s.get("/api/auth/sessions", "Devices/browsers where the user is signed in.", Auth::listSessions);
        s.delete("/api/auth/sessions/{session_id}", "Revoke Session", Auth::revokeSession);
        s.get("/api/auth/activity", "My Activity", Auth::myActivity);
    }

    static Object register(Req req) {
        Body b = Body.of(req);
        String fullName = b.check("full_name", b.str("full_name"), Auth::cleanFullName);
        String username = b.check("username", b.str("username"), Auth::cleanUsername);
        String email = b.check("email", b.str("email"), Auth::cleanEmail);
        String password = b.str("password");
        String role = b.literal("role", "user", "admin", "user");
        String adminCode = b.str("admin_code", "");
        b.done();
        Db db = req.db();
        REGISTER_LIMITER.check(req.clientIp());
        if (!registrationOpen(db)) throw new ApiError(403, "Registration is closed. Ask an admin to create an account for you.");
        checkAdminCode(db, req, role, adminCode, username);
        checkPasswordStrength(password, username, email);
        String conflict = accountConflict(db, username, email, null);
        if (conflict != null) throw new ApiError(409, conflict);
        EmailCheck.validateRealEmail(email);

        // Nothing is created yet: the account is made when the emailed code is entered (verify-code)
        LocalDateTime now = Py.utcnow();
        db.execute("DELETE FROM pending_registrations WHERE expires_at <= ? OR email = ?", now, email);
        PendingRegistration pending = new PendingRegistration();
        pending.username = username;
        pending.email = email;
        pending.fullName = fullName;
        pending.role = role;
        pending.passwordHash = hashPassword(password);
        pending.codeHash = "";
        pending.expiresAt = now.plusMinutes(Config.VERIFICATION_CODE_MINUTES());
        db.add(pending);
        db.flush();
        String code = Crypto.sixDigits();
        pending.codeHash = registrationCodeHash(pending, code);
        try {
            deliverCodeEmail(pending.email, Py.or(pending.fullName, pending.username), code, "login");
        } catch (Exception exc) {
            db.rollback();  // nothing is kept
            throw new ApiError(502, "We could not send a verification email to " + email + ". Check that the "
                    + "address is correct and try again. (" + exc.getMessage() + ")");
        }
        logEvent(db, req, "registration_started", null, username, true, "role: " + role);
        db.commit();
        return Py.map("otp_required", true, "registration", true, "login", pending.username, "email", pending.email,
                "message", "We've sent a 6-digit code to " + pending.email + ". Your account is created when you enter it.");
    }

    /** The sign-up's code is right: create the account and sign in, in one transaction. */
    static Object finishRegistration(Db db, Req req, PendingRegistration pending, boolean rememberMe) {
        String conflict = accountConflict(db, pending.username, pending.email, null);
        if (conflict != null) {  // someone else finished a sign-up with this name or address first
            db.delete(pending);
            db.commit();
            throw new ApiError(409, conflict + " Someone finished creating it first: create your account again.");
        }
        purgeAbandoned(db, pending.username, pending.email);
        LocalDateTime now = Py.utcnow();
        User user = new User();
        user.username = pending.username;
        user.email = pending.email;
        user.fullName = pending.fullName;
        user.role = pending.role;
        user.passwordHash = pending.passwordHash;
        user.passwordChangedAt = now;
        user.emailVerified = true;
        user.emailVerifiedAt = now;
        user.lastLoginAt = now;
        db.add(user);
        db.delete(pending);
        db.flush();
        logEvent(db, req, "register", user, "role: " + user.role + "; email confirmed with the code");
        startSession(db, req, user, rememberMe, null);
        if (rememberMe) trustDevice(db, req, user);
        logEvent(db, req, "login_success", user, "new account");
        db.commit();
        return tokenResponse(user);
    }

    static void checkRegistrationCode(Db db, Req req, PendingRegistration pending, String code) {
        if (pending == null || pending.attempts >= Config.VERIFICATION_MAX_ATTEMPTS) {
            throw new ApiError(400, "This code has expired or was replaced. Request a new code.");
        }
        if (!Crypto.equal(pending.codeHash, registrationCodeHash(pending, Py.strip(code)))) {
            pending.attempts += 1;
            int left = Config.VERIFICATION_MAX_ATTEMPTS - pending.attempts;
            logEvent(db, req, "login_code_failed", null, pending.username, false, "sign-up code");
            db.commit();
            if (left <= 0) throw new ApiError(400, "Too many wrong codes. Request a new code.");
            throw new ApiError(400, "Incorrect code. " + left + " attempt(s) left.");
        }
    }

    static Object verifyCode(Req req) {
        Body b = Body.of(req);
        String login = b.check("login", b.str("login"), Auth::normalizeLogin);
        String code = b.str("code");
        boolean rememberMe = b.bool("remember_me", false);
        boolean registration = b.bool("registration", false);
        b.done();
        Db db = req.db();
        VERIFY_LIMITER.check(req.clientIp());
        if (registration) {  // a new sign-up: the account is created now
            PendingRegistration pending = pendingRegistration(db, login);
            checkRegistrationCode(db, req, pending, code);
            return finishRegistration(db, req, pending, rememberMe);
        }
        User user = findUser(db, login);
        if (user == null || Py.isEmpty(user.email)) throw new ApiError(400, "This code has expired or was replaced. Request a new code.");
        if (!Boolean.TRUE.equals(user.isActive)) throw new ApiError(403, "This account has been disabled.");
        try {
            checkCode(db, user, validRecoveryCode(db, user) != null ? "recovery" : "login", user.email, code);
        } catch (ApiError e) {
            logEvent(db, req, "login_code_failed", user, "", false, e.text());
            db.commit();
            throw e;
        }
        if (!Boolean.TRUE.equals(user.emailVerified)) {  // the code reached this inbox, so the address is real
            user.emailVerified = true;
            user.emailVerifiedAt = Py.utcnow();
        }
        user.lastLoginAt = Py.utcnow();
        user.failedLoginCount = 0;
        user.lockedUntil = null;
        startSession(db, req, user, rememberMe, null);
        if (rememberMe) trustDevice(db, req, user);  // next time: password only on this browser
        logEvent(db, req, "login_success", user, rememberMe ? "remember me; device trusted" : "");
        db.commit();
        return tokenResponse(user);
    }

    static Object resendCode(Req req) {
        Body b = Body.of(req);
        String login = b.check("login", b.str("login"), Auth::normalizeLogin);
        boolean registration = b.bool("registration", false);
        b.done();
        Db db = req.db();
        RESEND_LIMITER.check(req.clientIp());
        if (registration) return resendRegistrationCode(db, req, login);
        User user = findUser(db, login);
        ApiError startOver = new ApiError(400, "Your sign-in has expired. Please sign in again.");
        if (user == null || Py.isEmpty(user.email) || !Boolean.TRUE.equals(user.isActive)) throw startOver;
        LocalDateTime windowStart = Py.utcnow().minusMinutes(Config.VERIFICATION_CODE_MINUTES());
        long recent = db.count("SELECT COUNT(*) FROM email_verification_codes WHERE user_id = ? AND purpose = 'login' AND created_at > ?",
                user.id, windowStart);
        if (recent == 0) throw startOver;
        if (recent >= MAX_LOGIN_CODES) throw new ApiError(429, "Too many codes requested. Please wait a few minutes and sign in again.");
        if (!sendVerificationCode(db, user, user.email, "login", true)) {
            throw new ApiError(429, "Please wait " + Config.VERIFICATION_RESEND_SECONDS + " seconds before asking for another code.");
        }
        logEvent(db, req, "login_code_sent", user, "resent");
        db.commit();
        return Py.map("message", "A new code has been sent to " + user.email + ".", "resend_after", Config.VERIFICATION_RESEND_SECONDS);
    }

    static Object resendRegistrationCode(Db db, Req req, String login) {
        PendingRegistration pending = pendingRegistration(db, login);
        if (pending == null) throw new ApiError(400, "Your sign-up has expired. Please create your account again.");
        if (Duration.between(pending.sentAt, Py.utcnow()).getSeconds() < Config.VERIFICATION_RESEND_SECONDS) {
            throw new ApiError(429, "Please wait " + Config.VERIFICATION_RESEND_SECONDS + " seconds before asking for another code.");
        }
        if (pending.codesSent >= MAX_LOGIN_CODES) {
            throw new ApiError(429, "Too many codes requested. Please create your account again in a few minutes.");
        }
        String code = Crypto.sixDigits();
        try {
            deliverCodeEmail(pending.email, Py.or(pending.fullName, pending.username), code, "login");
        } catch (Exception exc) {
            throw new ApiError(502, "We could not send a verification email to " + pending.email + ". (" + exc.getMessage() + ")");
        }
        LocalDateTime now = Py.utcnow();
        pending.codeHash = registrationCodeHash(pending, code);
        pending.attempts = 0;
        pending.sentAt = now;
        pending.codesSent += 1;
        pending.expiresAt = now.plusMinutes(Config.VERIFICATION_CODE_MINUTES());
        logEvent(db, req, "login_code_sent", null, pending.username, true, "sign-up code resent");
        db.commit();
        return Py.map("message", "A new code has been sent to " + pending.email + ".", "resend_after", Config.VERIFICATION_RESEND_SECONDS);
    }

    static Object login(Req req) {
        Body b = Body.of(req);
        String login = b.check("login", b.str("login"), Auth::normalizeLogin);
        String password = b.str("password");
        boolean rememberMe = b.bool("remember_me", false);
        b.done();
        Db db = req.db();
        LOGIN_LIMITER.check(req.clientIp());
        User user = findUser(db, login);
        ApiError invalid = new ApiError(401, "Invalid username/email or password.");

        if (user == null) {
            verifyPassword(password, DUMMY_HASH);
            logEvent(db, req, "login_failed", null, login, false, "unknown account");
            db.commit();
            throw invalid;
        }

        LocalDateTime now = Py.utcnow();
        if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
            long minutes = Duration.between(now, user.lockedUntil).getSeconds() / 60 + 1;
            logEvent(db, req, "login_blocked", user, "", false, "account locked");
            db.commit();
            throw new ApiError(423, "Too many failed attempts. This account is locked for " + minutes + " more minute(s).");
        }

        if (!verifyPassword(password, user.passwordHash)) {
            user.failedLoginCount += 1;
            String detail = "wrong password (" + user.failedLoginCount + "/" + Config.MAX_FAILED_LOGINS() + ")";
            if (user.failedLoginCount >= Config.MAX_FAILED_LOGINS()) {
                user.lockedUntil = now.plusMinutes(Config.LOCKOUT_MINUTES());
                user.failedLoginCount = 0;
                detail = "wrong password, account locked";
            }
            logEvent(db, req, "login_failed", user, "", false, detail);
            db.commit();
            if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
                throw new ApiError(423, "Too many failed attempts. This account is locked for " + Config.LOCKOUT_MINUTES() + " minutes.");
            }
            int left = Config.MAX_FAILED_LOGINS() - user.failedLoginCount;
            throw new ApiError(401, left <= 2 ? "Invalid username/email or password. " + left
                    + " attempt(s) left before the account is locked." : invalid.text());
        }

        if (!Boolean.TRUE.equals(user.isActive)) {
            logEvent(db, req, "login_blocked", user, "", false, "account disabled");
            db.commit();
            throw new ApiError(403, "This account has been disabled. Contact your system administrator.");
        }

        // Password is right.
        user.failedLoginCount = 0;

        // "Keep me signed in" on a browser that already passed the emailed code: no code needed
        TrustedDevice device = rememberMe ? trustedDevice(db, req, user) : null;
        if (device != null) {
            device.lastUsedAt = now;
            user.lockedUntil = null;
            user.lastLoginAt = now;
            startSession(db, req, user, true, null);
            logEvent(db, req, "login_success", user, "remember me; trusted device, no code");
            db.commit();
            return tokenResponse(user);
        }
        if (!rememberMe) forgetDevice(db, req);  // box unticked: this browser needs the code again

        // Step 2: a code emailed to the account's address.
        if (Py.isEmpty(user.email)) {
            db.commit();
            throw new ApiError(403, "This account has no email address, so a sign-in code cannot be sent. Ask another user to add one.");
        }
        try {
            EmailCheck.validateRealEmail(user.email);
        } catch (ApiError e) {
            if (e.status == 400) {
                db.commit();
                throw new ApiError(403, "The email on this account (" + user.email + ") cannot receive mail, so a "
                        + "sign-in code cannot be sent. Ask another user to correct it.");
            }
            throw e;
        }
        Map<String, Object> answer = sendLoginCode(db, req, user);
        db.commit();
        return answer;
    }

    static Object refresh(Req req) {
        Db db = req.db();
        RefreshToken token = currentRefreshToken(db, req);
        if (token == null) {
            clearRefreshCookie(req);
            throw unauthorized("Please sign in.");
        }
        LocalDateTime now = Py.utcnow();
        User user = token.user();

        if (token.revokedAt != null) {
            if (token.replacedById != null && Duration.between(token.revokedAt, now).getSeconds() <= REFRESH_REUSE_GRACE_SECONDS) {
                // Another tab refreshed a moment ago and the browser already has the new cookie
                if (Boolean.TRUE.equals(user.isActive)) return tokenResponse(user);
            }
            if (token.replacedById != null) {
                // An old, already-rotated token was used again: treat the session as stolen
                db.execute("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", now, token.familyId);
                logEvent(db, req, "refresh_reuse", user, "", false, "old refresh token re-used; session revoked");
                db.commit();
            }
            clearRefreshCookie(req);
            throw unauthorized("Your session has ended. Please sign in again.");
        }

        if (!token.expiresAt.isAfter(now) || !Boolean.TRUE.equals(user.isActive)) {
            token.revokedAt = now;
            db.commit();
            clearRefreshCookie(req);
            throw unauthorized("Your session has expired. Please sign in again.");
        }

        RefreshToken newToken = startSession(db, req, user, Boolean.TRUE.equals(token.rememberMe), token.familyId);
        newToken.createdAt = token.createdAt;  // the session started when the user signed in
        token.revokedAt = now;
        token.replacedById = newToken.id;
        db.commit();
        return tokenResponse(user);
    }

    static Object logout(Req req) {
        Db db = req.db();
        RefreshToken token = currentRefreshToken(db, req);
        if (token != null) {
            db.execute("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", Py.utcnow(), token.familyId);
            logEvent(db, req, "logout", token.user(), "");
            db.commit();
        }
        clearRefreshCookie(req);
        return Py.map("message", "Signed out.");
    }

    static Object logoutAll(Req req) {
        User user = currentUser(req);
        Db db = req.db();
        revokeAllSessions(db, user, null);
        revokeTrustedDevices(db, user);
        user.tokenVersion += 1;
        logEvent(db, req, "logout_all", user, "");
        db.commit();
        clearRefreshCookie(req);
        return Py.map("message", "Signed out on all devices.");
    }

    static Object updateProfile(Req req) {
        User user = currentUser(req);
        Body b = Body.of(req);
        String fullName = b.check("full_name", b.str("full_name"), Auth::cleanFullName);
        String email = b.check("email", b.str("email"), Auth::cleanEmail);
        b.done();
        Db db = req.db();
        if (!fullName.equals(user.fullName)) {
            user.fullName = fullName;
            logEvent(db, req, "profile_updated", user, "changed name");
        }
        String message = "Profile saved.";
        if (!email.equals(user.email) && !email.equals(user.pendingEmail)) {
            if (accountConflict(db, null, email, user.id) != null) throw new ApiError(409, "Another account already uses this email.");
            EmailCheck.validateRealEmail(email);
            user.pendingEmail = email;
            sendVerificationCode(db, user, email, "change", false);
            logEvent(db, req, "email_change_requested", user, email);
            message = "We've sent a 6-digit code to " + email + ". Enter it to confirm the new address.";
        } else if (email.equals(user.email) && !Py.isEmpty(user.pendingEmail)) {
            user.pendingEmail = null;  // typed the old address back: cancel the change
        }
        db.commit();
        Map<String, Object> out = userToDict(user);
        out.put("message", message);
        return out;
    }

    static Object confirmEmailChange(Req req) {
        User user = currentUser(req);
        Body b = Body.of(req);
        String code = b.str("code");
        b.done();
        Db db = req.db();
        if (Py.isEmpty(user.pendingEmail)) throw new ApiError(400, "There is no email change waiting for confirmation.");
        checkCode(db, user, "change", user.pendingEmail, code);
        if (accountConflict(db, null, user.pendingEmail, user.id) != null) {
            throw new ApiError(409, "Another account started using this email in the meantime.");
        }
        purgeAbandoned(db, null, user.pendingEmail);
        String old = user.email;
        user.email = user.pendingEmail;
        user.pendingEmail = null;
        user.emailVerified = true;
        user.emailVerifiedAt = Py.utcnow();
        logEvent(db, req, "email_changed", user, old + " -> " + user.email);
        db.commit();
        Map<String, Object> out = userToDict(user);
        out.put("message", "Your email address has been changed.");
        return out;
    }

    static Object resendEmailChangeCode(Req req) {
        User user = currentUser(req);
        Db db = req.db();
        if (Py.isEmpty(user.pendingEmail)) throw new ApiError(400, "There is no email change waiting for confirmation.");
        if (!sendVerificationCode(db, user, user.pendingEmail, "change", true)) {
            throw new ApiError(429, "Please wait " + Config.VERIFICATION_RESEND_SECONDS + " seconds before asking for another code.");
        }
        db.commit();
        return Py.map("message", "A new code has been sent to " + user.pendingEmail + ".");
    }

    static Object cancelEmailChange(Req req) {
        User user = currentUser(req);
        Db db = req.db();
        user.pendingEmail = null;
        db.execute("UPDATE email_verification_codes SET used_at = ? WHERE user_id = ? AND purpose = 'change' AND used_at IS NULL",
                Py.utcnow(), user.id);
        db.commit();
        return userToDict(user);
    }

    static Object changePassword(Req req) {
        User user = currentUser(req);
        Body b = Body.of(req);
        String current = b.str("current_password");
        String newPassword = b.str("new_password");
        b.done();
        Db db = req.db();
        if (!verifyPassword(current, user.passwordHash)) {
            logEvent(db, req, "password_change_failed", user, "", false, "wrong current password");
            db.commit();
            throw new ApiError(400, "Current password is incorrect.");
        }
        if (newPassword.equals(current)) throw new ApiError(400, "The new password must be different from the current one.");
        checkPasswordStrength(newPassword, user.username, Py.or(user.email, ""));

        RefreshToken token = currentRefreshToken(db, req);
        boolean remember = token != null && Boolean.TRUE.equals(token.rememberMe);
        user.passwordHash = hashPassword(newPassword);
        user.passwordChangedAt = Py.utcnow();
        user.tokenVersion += 1;                // old access tokens everywhere stop working
        revokeAllSessions(db, user, null);     // sign out other devices...
        revokeTrustedDevices(db, user);        // ...and every browser needs the emailed code again
        startSession(db, req, user, remember, null);  // ...but keep this one signed in
        logEvent(db, req, "password_changed", user, "other sessions signed out");
        db.commit();
        return tokenResponse(user);
    }

    static Object forgotPassword(Req req) {
        Body b = Body.of(req);
        String email = b.check("email", b.str("email"), Auth::normalizeLogin);
        b.done();
        Db db = req.db();
        FORGOT_LIMITER.check(req.clientIp());
        Map<String, Object> answer = Py.map("message", "If an account exists for that email, a password reset link has been sent. "
                + "The link is valid for " + Config.PASSWORD_RESET_MINUTES() + " minutes.");
        User user = db.first(User.class, "WHERE t.email = ?", email);
        if (user == null || !Boolean.TRUE.equals(user.isActive)) {
            logEvent(db, req, "password_reset_requested", null, email, false, "no active account");
            db.commit();
            return answer;
        }

        // Only the newest link works
        db.execute("UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL", Py.utcnow(), user.id);
        String raw = Crypto.tokenUrlsafe(32);
        PasswordResetToken reset = new PasswordResetToken();
        reset.userId = user.id;
        reset.tokenHash = hashToken(raw);
        reset.ipAddress = req.clientIp();
        reset.expiresAt = Py.utcnow().plusMinutes(Config.PASSWORD_RESET_MINUTES());
        db.add(reset);
        logEvent(db, req, "password_reset_requested", user, "");
        db.commit();
        String to = user.email, name = Py.or(user.fullName, user.username);
        req.afterResponse = () -> sendResetEmail(to, name, raw);
        return answer;
    }

    static void sendResetEmail(String email, String name, String rawToken) {
        String link = Config.APP_BASE_URL + "/?reset_token=" + rawToken;
        String body = "\n    <div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#1f2430;max-width:520px\">\n"
                + "      <p>Hello " + Py.escape(name) + ",</p>\n"
                + "      <p>We received a request to reset the password for your NPSB Dispute Email Automation account.</p>\n"
                + "      <p style=\"margin:24px 0\">\n"
                + "        <a href=\"" + link + "\" style=\"background:#e8222d;color:#fff;padding:10px 18px;border-radius:6px;\n"
                + "           text-decoration:none;font-weight:600\">Reset password</a>\n"
                + "      </p>\n"
                + "      <p>Or open this link: <br><a href=\"" + link + "\">" + link + "</a></p>\n"
                + "      <p>The link is valid for " + Config.PASSWORD_RESET_MINUTES() + " minutes and can be used once.\n"
                + "         If you did not ask for this, you can ignore this email; your password stays the same.</p>\n"
                + "    </div>";
        try {
            EmailService.sendEmail(List.of(email), List.of(), "Reset your password", body, null, null, null);
        } catch (Exception exc) {  // never crash the request; the log shows what happened
            System.out.println("Password reset email to " + email + " failed: " + exc.getMessage());
        }
    }

    static PasswordResetToken validReset(Db db, String token) {
        PasswordResetToken reset = db.first(PasswordResetToken.class, "WHERE t.token_hash = ?", hashToken(token));
        if (reset == null || reset.usedAt != null || !reset.expiresAt.isAfter(Py.utcnow())) {
            throw new ApiError(400, "This reset link is invalid or has expired. Please request a new one.");
        }
        return reset;
    }

    static Object checkResetToken(Req req) {
        PasswordResetToken reset = validReset(req.db(), req.path("token"));
        return Py.map("username", reset.user().username);
    }

    static Object resetPassword(Req req) {
        Body b = Body.of(req);
        String token = b.str("token");
        String newPassword = b.str("new_password");
        b.done();
        Db db = req.db();
        PasswordResetToken reset = validReset(db, token);
        User user = reset.user();
        checkPasswordStrength(newPassword, user.username, Py.or(user.email, ""));

        user.passwordHash = hashPassword(newPassword);
        user.passwordChangedAt = Py.utcnow();
        user.tokenVersion += 1;
        user.failedLoginCount = 0;
        user.lockedUntil = null;
        if (!Boolean.TRUE.equals(user.emailVerified)) {  // the reset link reached this inbox, so the address is real
            user.emailVerified = true;
            user.emailVerifiedAt = Py.utcnow();
        }
        reset.usedAt = Py.utcnow();
        revokeAllSessions(db, user, null);
        revokeTrustedDevices(db, user);
        logEvent(db, req, "password_reset", user, "all sessions signed out");
        db.commit();
        return Py.map("message", "Your password has been reset. You can now sign in with the new password.");
    }

    static Object listSessions(Req req) {
        User user = currentUser(req);
        Db db = req.db();
        RefreshToken current = currentRefreshToken(db, req);
        List<Object> out = new ArrayList<>();
        for (RefreshToken t : db.all(RefreshToken.class, "WHERE t.user_id = ? AND t.revoked_at IS NULL AND t.expires_at > ? ORDER BY t.last_used_at DESC",
                user.id, Py.utcnow())) {
            out.add(Py.map(
                    "id", t.id,
                    "user_agent", t.userAgent,
                    "ip_address", t.ipAddress,
                    "signed_in_at", Py.isoUtc(t.createdAt),
                    "last_used_at", Py.isoUtc(t.lastUsedAt),
                    "expires_at", Py.isoUtc(t.expiresAt),
                    "remember_me", t.rememberMe,
                    "current", current != null && current.familyId.equals(t.familyId)));
        }
        return out;
    }

    static Object revokeSession(Req req) {
        User user = currentUser(req);
        int sessionId = req.pathInt("session_id");
        Db db = req.db();
        RefreshToken token = db.get(RefreshToken.class, sessionId);
        if (token == null || !token.userId.equals(user.id)) throw new ApiError(404, "Session not found.");
        db.execute("UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", Py.utcnow(), token.familyId);
        logEvent(db, req, "session_revoked", user, Py.cut(token.userAgent, 120));
        db.commit();
        return Py.map("message", "Session signed out.");
    }

    static Object myActivity(Req req) {
        User user = currentUser(req);
        List<Object> out = new ArrayList<>();
        for (AuthEvent e : req.db().all(AuthEvent.class, "WHERE t.user_id = ? ORDER BY t.created_at DESC LIMIT 25", user.id)) {
            out.add(eventToDict(e));
        }
        return out;
    }

    public static Map<String, Object> eventToDict(AuthEvent e) {
        return Py.map("id", e.id, "event", e.event, "success", e.success, "detail", e.detail,
                "username", e.username, "ip_address", e.ipAddress, "user_agent", e.userAgent,
                "created_at", Py.isoUtc(e.createdAt));
    }
}
