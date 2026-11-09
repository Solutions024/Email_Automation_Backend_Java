package com.nagad.npsb.security;

import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.web.ApiException;
import com.nagad.npsb.web.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The signed-in user of the current request (see {@link JwtAuthenticationFilter}). Endpoints that need one call
 * {@link #require()} (401 otherwise) or {@link #requireAdmin()} (403 for non-admins), as the FastAPI dependencies did.
 * The returned User belongs to the request's persistence context, so changes to it are saved on commit.
 */
@Component
public class CurrentUser {
    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    /** The signed-in user, or 401 (with WWW-Authenticate: Bearer). */
    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) {
            return users.findById(principal.id()).orElseThrow(() -> unauthorized(JwtAuthenticationFilter.ENDED));
        }
        HttpServletRequest request = ClientInfo.currentRequest();
        Object problem = request == null ? null : request.getAttribute(JwtAuthenticationFilter.PROBLEM_ATTRIBUTE);
        throw unauthorized(problem instanceof String s ? s : JwtAuthenticationFilter.SIGN_IN);
    }

    /** The signed-in user if they are an admin, otherwise 403. */
    public User requireAdmin() {
        User user = require();
        if (!"admin".equals(user.getRole())) throw new ApiException(403, "Only admins can do this.");
        return user;
    }

    /** The signed-in user, if the request is signed in. */
    public Optional<User> optional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser principal) return users.findById(principal.id());
        return Optional.empty();
    }

    /**
     * The 401 for a request that is not signed in (null when it is). Used where Spring rejects a request before the
     * endpoint runs (a non-numeric id, an oversized upload): every such endpoint needs a sign-in, and the FastAPI
     * backend checked the sign-in first.
     */
    public static ApiException notSignedIn(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser) return null;
        Object problem = request.getAttribute(JwtAuthenticationFilter.PROBLEM_ATTRIBUTE);
        return unauthorized(problem instanceof String s ? s : JwtAuthenticationFilter.SIGN_IN);
    }

    /** 401 with WWW-Authenticate: Bearer. */
    public static ApiException unauthorized(String message) {
        return new ApiException(401, message).header("WWW-Authenticate", "Bearer");
    }
}
