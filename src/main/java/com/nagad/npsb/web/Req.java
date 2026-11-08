package com.nagad.npsb.web;

import com.nagad.npsb.PyJson;
import com.nagad.npsb.db.Db;
import com.nagad.npsb.models.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One API request: path and query parameters, JSON body, cookies, the caller, and its database session. */
public final class Req {
    public static final Object NO_BODY = new Object();

    public final HttpServletRequest http;
    public final Res res = new Res();
    private final Map<String, String> pathParams;
    private byte[] raw;
    private Object json;
    private Db db;
    /** The signed-in user, once checked (get_current_user is cached per request, like a FastAPI dependency). */
    public User user;
    /** BackgroundTasks: runs after the response has been sent. */
    public Runnable afterResponse;

    Req(HttpServletRequest http, Map<String, String> pathParams) {
        this.http = http;
        this.pathParams = pathParams;
    }

    /** The request's database session (closed when the response has been written). */
    public Db db() {
        if (db == null) db = Db.open();
        return db;
    }

    void closeDb() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    // ---------------------------------------------------------------- path and query

    public String path(String name) {
        return pathParams.get(name);
    }

    /** An int path parameter (422 when it isn't a number). */
    public int pathInt(String name) {
        String v = pathParams.get(name);
        try {
            return Integer.parseInt(v.strip().startsWith("+") ? v.strip().substring(1) : v.strip());
        } catch (NumberFormatException e) {
            throw new ValidationError(List.of(Body.error("int_parsing", List.of("path", name),
                    "Input should be a valid integer, unable to parse string as an integer", v)));
        }
    }

    /** A query parameter (the last one when given more than once), or null. */
    public String query(String name) {
        String[] values = http.getParameterValues(name);
        if (values == null || values.length == 0) return null;
        return values[values.length - 1];
    }

    public String query(String name, String def) {
        String v = query(name);
        return v == null ? def : v;
    }

    /** int | None query parameter. */
    public Integer queryInt(String name, Integer def) {
        String v = query(name);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.strip().startsWith("+") ? v.strip().substring(1) : v.strip());
        } catch (NumberFormatException e) {
            throw new ValidationError(List.of(Body.error("int_parsing", List.of("query", name),
                    "Input should be a valid integer, unable to parse string as an integer", v)));
        }
    }

    /** Literal[...] query parameter. */
    public String queryLiteral(String name, String def, String... allowed) {
        String v = query(name);
        if (v == null) return def;
        for (String a : allowed) if (a.equals(v)) return a;
        throw new ValidationError(List.of(Body.error("literal_error", List.of("query", name),
                "Input should be " + Body.expected(allowed), v)));
    }

    /** date | None query parameter (YYYY-MM-DD). */
    public LocalDate queryDate(String name) {
        String v = query(name);
        if (v == null) return null;
        try {
            String s = v.strip();
            if (s.length() > 10 && (s.charAt(10) == 'T' || s.charAt(10) == ' ') && s.substring(11).matches("00:00(:00(\\.0+)?)?Z?")) {
                s = s.substring(0, 10);
            }
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ValidationError(List.of(Body.error("date_from_datetime_parsing", List.of("query", name),
                    "Input should be a valid date or datetime, invalid character in year", v)));
        }
    }

    // ---------------------------------------------------------------- body

    public byte[] raw() {
        if (raw == null) {
            if (http.getContentLengthLong() > MAX_BODY_BYTES) {
                throw new ApiError(413, "The request is too large. The limit is " + MAX_BODY_BYTES / (1024 * 1024) + " MB.");
            }
            try (InputStream in = http.getInputStream()) {
                raw = in.readNBytes((int) MAX_BODY_BYTES + 1);  // no Content-Length (chunked): still capped
                if (raw.length > MAX_BODY_BYTES) {
                    throw new ApiError(413, "The request is too large. The limit is " + MAX_BODY_BYTES / (1024 * 1024) + " MB.");
                }
            } catch (IOException e) {
                raw = new byte[0];
            }
        }
        return raw;
    }

    /** The JSON body: NO_BODY when there is none; 422 when it isn't valid JSON. */
    public Object json() {
        if (json == null) {
            byte[] bytes = raw();
            if (bytes.length == 0) {
                json = NO_BODY;
            } else {
                try {
                    json = PyJson.MAPPER.readValue(bytes, Object.class);
                    if (json == null) json = NO_BODY;
                } catch (IOException e) {
                    throw new ValidationError(List.of(Body.error("json_invalid", List.of("body", 0), "JSON decode error", Map.of())));
                }
            }
        }
        return json;
    }

    /** An uploaded file of a multipart form (File(...)): 422 when it is missing. */
    /** Largest uploaded file, and largest request body (both are read into memory). */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024, MAX_BODY_BYTES = 30L * 1024 * 1024;

    public Part file(String name) {
        try {
            Part part = http.getPart(name);
            if (part != null && part.getSubmittedFileName() != null) return part;
        } catch (IllegalStateException e) {  // Tomcat: the file or the request is over the limit
            throw new ApiError(413, "The file is too large. The limit is " + MAX_FILE_BYTES / (1024 * 1024) + " MB.");
        } catch (Exception ignored) {
            // not a multipart request
        }
        throw new ValidationError(List.of(Body.error("missing", List.of("body", name), "Field required", null)));
    }

    // ---------------------------------------------------------------- headers, cookies, client

    public String header(String name) {
        return http.getHeader(name);
    }

    /** The cookie's value (the last one if the browser sent it twice), or null. */
    public String cookie(String name) {
        Cookie[] cookies = http.getCookies();
        if (cookies == null) return null;
        String found = null;
        for (Cookie c : cookies) if (c.getName().equals(name)) found = c.getValue();
        return found;
    }

    /** request.client.host (X-Forwarded-For is trusted from this machine only, like uvicorn). */
    public String clientIp() {
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

    public String userAgent() {
        String ua = http.getHeader("User-Agent");
        if (ua == null) return "";
        return ua.length() > 255 ? ua.substring(0, 255) : ua;
    }
}
