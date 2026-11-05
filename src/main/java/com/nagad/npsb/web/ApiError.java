package com.nagad.npsb.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** HTTPException: answered as {"detail": ...} with this status (and headers). */
public class ApiError extends RuntimeException {
    public final int status;
    public final Object detail;
    public final Map<String, String> headers = new LinkedHashMap<>();

    public ApiError(int status, Object detail) {
        super(detail instanceof String s ? s : String.valueOf(detail));
        this.status = status;
        this.detail = detail;
    }

    public ApiError header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    /** The detail as text (str(exc.detail)). */
    public String text() {
        return detail instanceof String s ? s : String.valueOf(detail);
    }
}
