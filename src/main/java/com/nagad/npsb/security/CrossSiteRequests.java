package com.nagad.npsb.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * The requests Spring Security's CSRF protection refuses: a state-changing request (POST, PUT, PATCH, DELETE) that a
 * browser sent from another site. The browser says where a request comes from in headers a page can't set or change:
 * Sec-Fetch-Site (all current browsers) or, in older ones, Origin.
 *
 * This frontend's requests pass: they come from the page's own address (Sec-Fetch-Site: same-origin; through the dev
 * server's /api proxy), from the same site (another port of the same host), or from the frontend address that CORS
 * allows (APP_BASE_URL). So do requests without these headers: they don't come from a web page (curl, scripts, tests).
 *
 * Why no CSRF token: the API is signed in with a bearer token in the Authorization header, which a page on another
 * site can't send, and the refresh and trusted-device cookies are HttpOnly and SameSite=Strict. This check backs
 * those up.
 */
public final class CrossSiteRequests implements RequestMatcher {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final String frontendOrigin;

    /** frontendOrigin: the frontend's scheme://host[:port] (NpsbProperties.frontendOrigin()). */
    public CrossSiteRequests(String frontendOrigin) {
        this.frontendOrigin = frontendOrigin == null ? "" : frontendOrigin.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) return false;
        String origin = request.getHeader("Origin");
        if (origin != null && origin.toLowerCase(Locale.ROOT).equals(frontendOrigin)) return false;
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) return site.equalsIgnoreCase("cross-site");
        if (origin == null) return false;                      // not sent by a web page
        return !sameHost(origin, request.getHeader("Host"));   // older browsers: Origin only
    }

    /** The Origin's host[:port] is the address the request was sent to ("null" or unreadable: no). */
    private static boolean sameHost(String origin, String host) {
        if (host == null) return false;
        try {
            URI uri = URI.create(origin);
            if (uri.getHost() == null) return false;
            String authority = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
