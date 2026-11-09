package com.nagad.npsb.service.auth;

import com.nagad.npsb.dto.auth.UserCreateRequest;
import com.nagad.npsb.dto.auth.UserUpdateRequest;
import com.nagad.npsb.entity.AuthEvent;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.AuthEventRepository;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.service.AuthService;
import com.nagad.npsb.service.EmailCheck;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * User management and the security audit log (/api/users). The built-in admin only (other admins can't manage
 * accounts): UserController checks that (AuthService.requireBuiltInAdmin) before calling these.
 */
@Service
public class UserAdminService {
    private final UserRepository users;
    private final AuthEventRepository authEvents;
    private final AuthService auth;
    private final EmailCheck emailCheck;
    private final JdbcClient jdbc;
    private final EntityManager entityManager;

    public UserAdminService(UserRepository users, AuthEventRepository authEvents, AuthService auth, EmailCheck emailCheck,
                            JdbcClient jdbc, EntityManager entityManager) {
        this.users = users;
        this.authEvents = authEvents;
        this.auth = auth;
        this.emailCheck = emailCheck;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    long otherActiveAdmins(User user) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE `role` = 'admin' AND is_active = ? AND id != ?")
                .params(true, user.getId()).query(Long.class).single();
    }

    /** GET /api/users */
    public List<Object> listUsers() {
        List<Object> out = new ArrayList<>();
        for (User u : users.findAllByOrderByUsernameAsc()) out.add(AuthService.userToDict(u));
        return out;
    }

    /** POST /api/users: create an account for someone. They sign in with their password plus a code emailed to them. */
    @Transactional
    public Map<String, Object> createUser(User me, UserCreateRequest in) {
        String username = in.username(), email = in.email();
        AuthService.checkPasswordStrength(in.password(), username, email);
        String conflict = auth.accountConflict(username, email, null);
        if (conflict != null) throw new ApiException(409, conflict);
        emailCheck.validateRealEmail(email);
        auth.purgeAbandoned(username, email);  // unfinished sign-ups don't hold the name or address
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(in.fullName());
        user.setRole(in.role());
        user.setPasswordHash(AuthService.hashPassword(in.password()));
        user.setPasswordChangedAt(Py.utcnow());
        users.save(user);
        entityManager.flush();
        auth.logEvent("user_created", me, "created " + user.getUsername() + " (" + user.getRole() + ")");
        return AuthService.userToDict(user);
    }

    /** PATCH /api/users/{user_id} */
    @Transactional
    public Map<String, Object> updateUser(User me, int userId, UserUpdateRequest in) {
        User user = users.findById(userId).orElse(null);
        if (user == null) throw new ApiException(404, "User not found.");
        String role = in.role();
        Boolean isActive = in.isActive();
        String newPassword = in.password();

        List<String> changes = new ArrayList<>();
        if (in.unlock() && (user.getLockedUntil() != null || (user.getFailedLoginCount() != null && user.getFailedLoginCount() != 0))) {
            user.setLockedUntil(null);
            user.setFailedLoginCount(0);
            changes.add("unlocked");
        }

        if (newPassword != null) {
            if (user.getId().equals(me.getId())) throw new ApiException(400, "Change your own password on My Profile.");
            AuthService.checkPasswordStrength(newPassword, user.getUsername(), user.getEmail());
            user.setPasswordHash(AuthService.hashPassword(newPassword));
            user.setPasswordChangedAt(Py.utcnow());
            user.setFailedLoginCount(0);
            user.setLockedUntil(null);
            user.setTokenVersion(user.getTokenVersion() + 1);  // signed out everywhere: the old password no longer works
            auth.revokeAllSessions(user);
            auth.revokeTrustedDevices(user);
            changes.add("password set by an admin");
        }

        if (AuthService.isBuiltInAdmin(user) && ((role != null && !role.equals(user.getRole())) || Boolean.FALSE.equals(isActive))) {
            throw new ApiException(400, "The built-in admin can't be made a user or deactivated.");
        }

        if (role != null && !role.equals(user.getRole())) {
            if (user.getId().equals(me.getId())) throw new ApiException(400, "You can't change your own role. Ask another admin.");
            if ("admin".equals(user.getRole()) && Boolean.TRUE.equals(user.getIsActive()) && otherActiveAdmins(user) == 0) {
                throw new ApiException(400, "This is the last active admin. Make someone else an admin first.");
            }
            user.setRole(role);
            changes.add("role set to " + role);
        }

        if (isActive != null && !isActive.equals(user.getIsActive())) {
            if (user.getId().equals(me.getId())) throw new ApiException(400, "You can't deactivate your own account. Ask another admin.");
            if (!isActive && "admin".equals(user.getRole()) && otherActiveAdmins(user) == 0) {
                throw new ApiException(400, "This is the last active admin. Make someone else an admin first.");
            }
            user.setIsActive(isActive);
            if (!isActive) {  // signed out everywhere at once
                user.setTokenVersion(user.getTokenVersion() + 1);
                auth.revokeAllSessions(user);
                auth.revokeTrustedDevices(user);
            }
            changes.add(isActive ? "activated" : "deactivated");
        }

        if (!changes.isEmpty()) auth.logEvent("user_updated", me, user.getUsername() + ": " + String.join(", ", changes));
        return AuthService.userToDict(user);
    }

    /** GET /api/users/events: the audit log, newest first. */
    public List<Object> auditLog(int limit) {
        List<Object> out = new ArrayList<>();
        for (AuthEvent e : authEvents.findAllByOrderByCreatedAtDescIdDesc(Limit.of(limit))) out.add(AuthService.eventToDict(e));
        return out;
    }
}
