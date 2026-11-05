package com.nagad.npsb.web;

import java.util.List;
import java.util.Map;

/** RequestValidationError: answered with 422 and the list of problems (as FastAPI / pydantic do). */
public class ValidationError extends RuntimeException {
    public final List<Map<String, Object>> errors;

    public ValidationError(List<Map<String, Object>> errors) {
        super(errors.toString());
        this.errors = errors;
    }
}
