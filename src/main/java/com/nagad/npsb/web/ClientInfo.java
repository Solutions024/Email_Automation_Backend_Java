package com.nagad.npsb.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

/** Who is calling: IP address and browser (for the audit log, sessions and rate limits). */
public final class ClientInfo {
    private ClientInfo() {}

    /** The current HTTP request, or null outside a request (background jobs). */
    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) return a.getRequest();
        return null;
    }

    /** The client's IP of the current request ("" outside a request). */
    public static String clientIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? "" : clientIp(request);
    }

    /** The client's User-Agent of the current request ("" outside a request). */
    public static String userAgent() {
        HttpServletRequest request = currentRequest();
        return request == null ? "" : userAgent(request);
    }

    /** request.client.host (X-Forwarded-For is trusted from this machine only, like uvicorn). At most 45 characters. */
    public static String clientIp(HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (ip != null && (ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1"))) {
            String forwarded = http.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                List<String> hops = new ArrayList<>();
                for (String h : forwarded.split(",")) if (!h.isBlank()) hops.add(h.strip());
                if (!hops.isEmpty()) ip = hops.get(hops.size() - 1);
            }
        }
        if (ip == null) return "";
        if (ip.equals("0:0:0:0:0:0:0:1")) ip = "::1";
        return ip.length() > 45 ? ip.substring(0, 45) : ip;
    }

    /** The User-Agent header, at most 255 characters. */
    public static String userAgent(HttpServletRequest http) {
        String ua = http.getHeader("User-Agent");
        if (ua == null) return "";
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }

    /** The cookie's value (the last one if the browser sent it twice), or null. */
    public static String cookie(HttpServletRequest http, String name) {
        jakarta.servlet.http.Cookie[] cookies = http.getCookies();
        if (cookies == null) return null;
        String found = null;
        for (jakarta.servlet.http.Cookie c : cookies) if (c.getName().equals(name)) found = c.getValue();
        return found;
    }
}
