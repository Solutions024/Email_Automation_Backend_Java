package com.nagad.npsb.web;

import java.util.List;
import java.util.Map;

/** Invalid request input: answered with 422 and the list of problems, as FastAPI / pydantic do. */
public class RequestValidationException extends RuntimeException {
    private final List<Map<String, Object>> errors;

    public RequestValidationException(List<Map<String, Object>> errors) {
        super(errors.toString());
        this.errors = errors;
    }

    public List<Map<String, Object>> errors() {
        return errors;
    }
}
