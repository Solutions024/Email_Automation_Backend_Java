package com.nagad.npsb.web;

import jakarta.servlet.http.HttpServletResponse;

/** Set-Cookie headers written exactly as Starlette's response.set_cookie / delete_cookie write them. */
public final class Cookies {
    private Cookies() {}

    /** response.set_cookie(...) */
    public static void set(HttpServletResponse response, String name, String value, Integer maxAge, boolean httpOnly,
                           boolean secure, String sameSite, String path) {
        StringBuilder b = new StringBuilder(name).append('=').append(value);
        if (httpOnly) b.append("; HttpOnly");
        if (maxAge != null) b.append("; Max-Age=").append(maxAge);
        b.append("; Path=").append(path);
        b.append("; SameSite=").append(sameSite);
        if (secure) b.append("; Secure");
        response.addHeader("Set-Cookie", b.toString());
    }

    /** response.delete_cookie(...) */
    public static void delete(HttpServletResponse response, String name, String path, boolean httpOnly, boolean secure, String sameSite) {
        StringBuilder b = new StringBuilder(name).append("=\"\"; expires=Thu, 01 Jan 1970 00:00:00 GMT");
        if (httpOnly) b.append("; HttpOnly");
        b.append("; Max-Age=0; Path=").append(path).append("; SameSite=").append(sameSite);
        if (secure) b.append("; Secure");
        response.addHeader("Set-Cookie", b.toString());
    }
}
