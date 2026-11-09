package com.nagad.npsb.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An HTTP error answered as {"detail": ...} with this status (and headers), like FastAPI's HTTPException.
 * Rendered by {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final Object detail;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiException(int status, Object detail) {
        super(detail instanceof String s ? s : String.valueOf(detail));
        this.status = status;
        this.detail = detail;
    }

    /** Add a response header (e.g. WWW-Authenticate). */
    public ApiException header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int status() {
        return status;
    }

    public Object detail() {
        return detail;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /** The detail as text (str(exc.detail)). */
    public String text() {
        return detail instanceof String s ? s : String.valueOf(detail);
    }
}
