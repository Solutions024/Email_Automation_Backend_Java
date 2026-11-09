package com.nagad.npsb.web;

import com.nagad.npsb.util.AppLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The [HTTP] lines of the application log: requests that change something, and every failed one (reads that work
 * are left out, and so are the health probes). Afterwards it starts the request's {@link BackgroundTasks}.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    private final BackgroundTasks backgroundTasks;

    public RequestLoggingFilter(BackgroundTasks backgroundTasks) {
        this.backgroundTasks = backgroundTasks;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                String method = request.getMethod();
                String path = request.getRequestURI().substring(request.getContextPath().length());
                int status = response.getStatus();
                boolean probe = path.equals("/startupz") || path.equals("/livez") || path.equals("/readyz");
                if (!probe && (!method.equals("GET") || status >= 400)) {
                    String text = AppLog.kv("method", method, "path", path, "status", status,
                            "ms", (System.nanoTime() - started) / 1_000_000, "ip", ClientInfo.clientIp(request),
                            "problem", request.getAttribute(GlobalExceptionHandler.PROBLEM_ATTRIBUTE));
                    if (status >= 500) LOG.warn("[HTTP] {}", text);
                    else AppLog.event("HTTP", text);
                }
            } finally {
                backgroundTasks.startAfterResponse(request);
            }
        }
    }
}
