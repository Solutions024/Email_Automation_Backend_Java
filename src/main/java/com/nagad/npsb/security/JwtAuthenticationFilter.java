package com.nagad.npsb.security;

import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.UserRepository;
import com.nagad.npsb.util.Crypto;
import com.nagad.npsb.util.Py;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Signs the request in from its "Authorization: Bearer <access token>" header: the token must be valid, the user
 * active, and the token issued since the last password change / sign-out everywhere (token_version).
 * When there is no usable token the request just stays anonymous; the reason is kept for {@link CurrentUser},
 * which answers 401 with it on endpoints that need a signed-in user.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    /** Request attribute: why the request is not signed in. */
    static final String PROBLEM_ATTRIBUTE = "npsb.authProblem";

    static final String SIGN_IN = "Please sign in.";
    static final String EXPIRED = "Your session has expired. Please sign in again.";
    static final String INVALID = "Invalid session. Please sign in again.";
    static final String ENDED = "Your session has ended. Please sign in again.";

    private final JwtService jwt;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String problem = authenticate(request);
        if (problem != null) request.setAttribute(PROBLEM_ATTRIBUTE, problem);
        chain.doFilter(request, response);
    }

    /** null when signed in, else why not. */
    private String authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = null;
        if (header != null) {
            String[] parts = header.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("bearer")) token = parts[1].strip();
        }
        if (token == null || token.isEmpty()) return SIGN_IN;
        Map<String, Object> payload;
        try {
            payload = jwt.decode(token);
        } catch (Crypto.JwtExpired e) {
            return EXPIRED;
        } catch (Crypto.JwtError e) {
            return INVALID;
        }
        if (!"access".equals(payload.get("type"))) return INVALID;
        int id;
        try {
            id = Integer.parseInt(Py.strip((String) payload.get("sub")));
        } catch (NumberFormatException | ClassCastException e) {
            return INVALID;
        }
        // loaded into the request's persistence context (OpenEntityManagerInViewFilter runs before this filter)
        User user = users.findById(id).orElse(null);
        Object ver = payload.get("ver");
        boolean sameVersion = user != null && user.getTokenVersion() != null && ver instanceof Number n
                && !(ver instanceof Double d && d != Math.rint(d)) && n.longValue() == user.getTokenVersion();
        if (user == null || !Boolean.TRUE.equals(user.getIsActive()) || !sameVersion) return ENDED;

        String role = user.getRole() == null ? "user" : user.getRole();
        var authentication = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(user.getId(), user.getUsername(), role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT))));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return null;
    }
}
