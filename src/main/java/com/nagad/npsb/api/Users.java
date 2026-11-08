package com.nagad.npsb.api;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.AuthEvent;
import com.nagad.npsb.models.User;
import com.nagad.npsb.service.EmailCheck;
import com.nagad.npsb.web.ApiError;
import com.nagad.npsb.web.Body;
import com.nagad.npsb.web.Req;
import com.nagad.npsb.web.Server;

import java.util.ArrayList;
import java.util.List;

/** User management and the security audit log. The built-in admin only (other admins can't manage accounts). */
public final class Users {
    private Users() {}

    public static void routes(Server s) {
        s.get("/api/users", "List Users", Users::listUsers);
        s.post("/api/users", 201, "Create an account for someone. An admin may create another admin without the admin code.",
                Users::createUser);
        s.patch("/api/users/{user_id}", "Update User", Users::updateUser);
        s.get("/api/users/events", "Audit Log", Users::auditLog);
    }

    static long otherActiveAdmins(Db db, User user) {
        return db.count("SELECT COUNT(*) FROM users WHERE `role` = 'admin' AND is_active = ? AND id != ?", true, user.id);
    }

    static Object listUsers(Req req) {
        Auth.requireBuiltInAdmin(req);
        List<Object> out = new ArrayList<>();
        for (User u : req.db().all(User.class, "ORDER BY t.username")) out.add(Auth.userToDict(u));
        return out;
    }

    /** Create an account for someone. They sign in with their password plus a code emailed to them. */
    static Object createUser(Req req) {
        User me = Auth.requireBuiltInAdmin(req);
        Body b = Body.of(req);
        String fullName = b.check("full_name", b.str("full_name"), Auth::cleanFullName);
        String username = b.check("username", b.str("username"), Auth::cleanUsername);
        String email = b.check("email", b.str("email"), Auth::cleanEmail);
        String password = b.str("password");
        String role = b.literal("role", "user", "admin", "user");
        b.str("admin_code", "");  // not needed: an admin creates the account (kept for older pages)
        b.done();
        Db db = req.db();
        Auth.checkPasswordStrength(password, username, email);
        String conflict = Auth.accountConflict(db, username, email, null);
        if (conflict != null) throw new ApiError(409, conflict);
        EmailCheck.validateRealEmail(email);
        Auth.purgeAbandoned(db, username, email);  // unfinished sign-ups don't hold the name or address
        User user = new User();
        user.username = username;
        user.email = email;
        user.fullName = fullName;
        user.role = role;
        user.passwordHash = Auth.hashPassword(password);
        user.passwordChangedAt = Py.utcnow();
        db.add(user);
        db.flush();
        Auth.logEvent(db, req, "user_created", me, "created " + user.username + " (" + user.role + ")");
        db.commit();
        return Auth.userToDict(user);
    }

    static Object updateUser(Req req) {
        User me = Auth.requireBuiltInAdmin(req);
        int userId = req.pathInt("user_id");
        Body b = Body.of(req);
        boolean unlock = b.bool("unlock", false);
        String role = b.optLiteral("role", "admin", "user");
        Boolean isActive = b.optBool("is_active");
        String newPassword = b.str("password", null);  // an admin sets a new password for someone else
        b.done();
        Db db = req.db();
        User user = db.get(User.class, userId);
        if (user == null) throw new ApiError(404, "User not found.");

        List<String> changes = new ArrayList<>();
        if (unlock && (user.lockedUntil != null || (user.failedLoginCount != null && user.failedLoginCount != 0))) {
            user.lockedUntil = null;
            user.failedLoginCount = 0;
            changes.add("unlocked");
        }

        if (newPassword != null) {
            if (user.id.equals(me.id)) throw new ApiError(400, "Change your own password on My Profile.");
            Auth.checkPasswordStrength(newPassword, user.username, user.email);
            user.passwordHash = Auth.hashPassword(newPassword);
            user.passwordChangedAt = Py.utcnow();
            user.failedLoginCount = 0;
            user.lockedUntil = null;
            user.tokenVersion += 1;  // signed out everywhere: the old password no longer works
            Auth.revokeAllSessions(db, user, null);
            Auth.revokeTrustedDevices(db, user);
            changes.add("password set by an admin");
        }

        if (Auth.isBuiltInAdmin(user) && ((role != null && !role.equals(user.role)) || Boolean.FALSE.equals(isActive))) {
            throw new ApiError(400, "The built-in admin can't be made a user or deactivated.");
        }

        if (role != null && !role.equals(user.role)) {
            if (user.id.equals(me.id)) throw new ApiError(400, "You can't change your own role. Ask another admin.");
            if ("admin".equals(user.role) && Boolean.TRUE.equals(user.isActive) && otherActiveAdmins(db, user) == 0) {
                throw new ApiError(400, "This is the last active admin. Make someone else an admin first.");
            }
            user.role = role;
            changes.add("role set to " + role);
        }

        if (isActive != null && !isActive.equals(user.isActive)) {
            if (user.id.equals(me.id)) throw new ApiError(400, "You can't deactivate your own account. Ask another admin.");
            if (!isActive && "admin".equals(user.role) && otherActiveAdmins(db, user) == 0) {
                throw new ApiError(400, "This is the last active admin. Make someone else an admin first.");
            }
            user.isActive = isActive;
            if (!isActive) {  // signed out everywhere at once
                user.tokenVersion += 1;
                Auth.revokeAllSessions(db, user, null);
                Auth.revokeTrustedDevices(db, user);
            }
            changes.add(isActive ? "activated" : "deactivated");
        }

        if (!changes.isEmpty()) Auth.logEvent(db, req, "user_updated", me, user.username + ": " + String.join(", ", changes));
        db.commit();
        return Auth.userToDict(user);
    }

    static Object auditLog(Req req) {
        Auth.requireBuiltInAdmin(req);
        int limit = Math.max(1, Math.min(req.queryInt("limit", 100), 500));
        List<Object> out = new ArrayList<>();
        for (AuthEvent e : req.db().all(AuthEvent.class, "ORDER BY t.created_at DESC, t.id DESC LIMIT " + limit)) {
            out.add(Auth.eventToDict(e));
        }
        return out;
    }
}
