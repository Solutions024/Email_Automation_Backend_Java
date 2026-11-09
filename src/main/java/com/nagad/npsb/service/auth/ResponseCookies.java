package com.nagad.npsb.service.auth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The Set-Cookie headers of a sign-in answer (refresh and trusted-device cookies, written with web.Cookies), held
 * back until the handler has succeeded: an error answer carries no cookies, as before (e.g. a refused refresh does
 * not clear the cookie). The controller passes this to AuthService as the response and calls {@link #send()} once
 * the handler has returned.
 */
public final class ResponseCookies extends HttpServletResponseWrapper {
    private static final String SET_COOKIE = "Set-Cookie";

    private final List<String> pending = new ArrayList<>();

    public ResponseCookies(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void addHeader(String name, String value) {
        if (SET_COOKIE.equalsIgnoreCase(name)) pending.add(value);
        else super.addHeader(name, value);
    }

    /** Write the collected cookies to the real response (the handler succeeded). */
    public void send() {
        HttpServletResponse response = (HttpServletResponse) getResponse();
        for (String cookie : pending) response.addHeader(SET_COOKIE, cookie);
        pending.clear();
    }
}
