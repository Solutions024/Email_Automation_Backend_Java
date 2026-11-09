package com.nagad.npsb.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Query parameters that need FastAPI's exact checks. Controllers take them as {@code @RequestParam(required = false) String}
 * and convert them here, so a bad value gets the same 422 answer as before. (Plain int / Integer parameters can use
 * Spring's conversion: a bad number is answered by GlobalExceptionHandler in the same format.)
 */
public final class QueryParams {
    private QueryParams() {}

    /** int | None query parameter. */
    public static Integer integer(String name, String value, Integer def) {
        if (value == null) return def;
        try {
            String v = value.strip();
            return Integer.parseInt(v.startsWith("+") ? v.substring(1) : v);
        } catch (NumberFormatException e) {
            throw new RequestValidationException(List.of(Body.error("int_parsing", List.of("query", name),
                    "Input should be a valid integer, unable to parse string as an integer", value)));
        }
    }

    /** Literal[...] query parameter: one of the allowed values, or def when not given. */
    public static String literal(String name, String value, String def, String... allowed) {
        if (value == null) return def;
        for (String a : allowed) if (a.equals(value)) return a;
        throw new RequestValidationException(List.of(Body.error("literal_error", List.of("query", name),
                "Input should be " + Body.expected(allowed), value)));
    }

    /** date | None query parameter (YYYY-MM-DD; a datetime at midnight is accepted too). */
    public static LocalDate date(String name, String value) {
        if (value == null) return null;
        try {
            String s = value.strip();
            if (s.length() > 10 && (s.charAt(10) == 'T' || s.charAt(10) == ' ') && s.substring(11).matches("00:00(:00(\\.0+)?)?Z?")) {
                s = s.substring(0, 10);
            }
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new RequestValidationException(List.of(Body.error("date_from_datetime_parsing", List.of("query", name),
                    "Input should be a valid date or datetime, invalid character in year", value)));
        }
    }
}
