package com.nagad.npsb.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * CORS for the frontend (APP_BASE_URL is the one allowed origin, with credentials), answered exactly like the
 * FastAPI backend's CORSMiddleware: a preflight from another origin gets 400 "Disallowed CORS origin".
 * Registered in WebConfig, before every other filter.
 */
public class CorsFilter extends OncePerRequestFilter {
    private final String allowedOrigin;

    public CorsFilter(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean allowed = origin != null && origin.equals(allowedOrigin);

        // preflight
        if ("OPTIONS".equals(request.getMethod()) && origin != null && request.getHeader("Access-Control-Request-Method") != null) {
            response.setHeader("Vary", "Origin");
            if (!allowed) {
                text(response, 400, "Disallowed CORS origin");
                return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Methods", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT");
            String requested = request.getHeader("Access-Control-Request-Headers");
            if (requested != null) response.setHeader("Access-Control-Allow-Headers", requested);
            response.setHeader("Access-Control-Max-Age", "600");
            text(response, 200, "OK");
            return;
        }
        if (allowed) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
        }
        chain.doFilter(request, response);
    }

    private static void text(HttpServletResponse response, int status, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("text/plain; charset=utf-8");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }
}
