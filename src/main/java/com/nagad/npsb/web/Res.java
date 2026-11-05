package com.nagad.npsb.web;

import java.util.ArrayList;
import java.util.List;

/**
 * What a handler adds to its answer besides the body: status code, extra headers and cookies
 * (the "response: Response" parameter of a FastAPI endpoint), or a file to download.
 */
public final class Res {
    public int status = 200;
    public final List<String[]> headers = new ArrayList<>();
    public final List<String> cookies = new ArrayList<>();

    /** A file answer (bytes with a content type) instead of JSON. */
    public static final class File {
        public final byte[] data;
        public final String contentType;
        public final String disposition;

        public File(byte[] data, String contentType, String disposition) {
            this.data = data;
            this.contentType = contentType;
            this.disposition = disposition;
        }
    }

    public static File file(byte[] data, String contentType, String disposition) {
        return new File(data, contentType, disposition);
    }

    /** response.set_cookie(...) */
    public void setCookie(String name, String value, Integer maxAge, boolean httpOnly, boolean secure, String sameSite, String path) {
        StringBuilder b = new StringBuilder(name).append('=').append(value);
        if (httpOnly) b.append("; HttpOnly");
        if (maxAge != null) b.append("; Max-Age=").append(maxAge);
        b.append("; Path=").append(path);
        b.append("; SameSite=").append(sameSite);
        if (secure) b.append("; Secure");
        cookies.add(b.toString());
    }

    /** response.delete_cookie(...) */
    public void deleteCookie(String name, String path, boolean httpOnly, boolean secure, String sameSite) {
        StringBuilder b = new StringBuilder(name).append("=\"\"; expires=Thu, 01 Jan 1970 00:00:00 GMT");
        if (httpOnly) b.append("; HttpOnly");
        b.append("; Max-Age=0; Path=").append(path).append("; SameSite=").append(sameSite);
        if (secure) b.append("; Secure");
        cookies.add(b.toString());
    }
}
