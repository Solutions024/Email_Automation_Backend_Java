package com.nagad.npsb.util;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON in the database is written exactly as Python's json.dumps() writes it (", " and ": " separators,
 * non-ASCII escaped), so values written by the Python backend and by this one compare equal
 * (maker-checker compares the stored "before" with the current state as text).
 */
public final class PyJson {
    private PyJson() {}

    /** Plain JSON reading / writing of maps and lists (numbers as Integer / Long / Double, like json.loads). */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    /** json.dumps(value) */
    public static String dumps(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, false);
        return b.toString();
    }

    /** json.dumps(value, sort_keys=True, default=str) */
    public static String dumpsSorted(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, true);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, boolean sort) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Boolean bool) {
            b.append(bool ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) b.append("NaN");
            else if (Double.isInfinite(d)) b.append(d > 0 ? "Infinity" : "-Infinity");
            else b.append(Py.floatRepr(d));
        } else if (v instanceof BigDecimal bd) {
            string(b, bd.toPlainString());          // default=str
        } else if (v instanceof Number n) {
            b.append(n.longValue());
        } else if (v instanceof String s) {
            string(b, s);
        } else if (v instanceof LocalDateTime d) {
            string(b, Py.iso(d).replace('T', ' '));  // str(datetime)
        } else if (v instanceof LocalDate d) {
            string(b, Py.iso(d));
        } else if (v instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            if (sort) map = new TreeMap<>(map);
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) b.append(", ");
                first = false;
                string(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), sort);
            }
            b.append('}');
        } else if (v instanceof Collection<?> c) {
            b.append('[');
            boolean first = true;
            for (Object item : c) {
                if (!first) b.append(", ");
                first = false;
                write(b, item, sort);
            }
            b.append(']');
        } else {
            string(b, v.toString());
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    /** json.loads(text): maps, lists, strings, numbers (Integer/Long/Double), booleans, null. */
    public static Object loads(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadsMap(String text) {
        return (Map<String, Object>) loads(text);
    }

    public static List<Map<String, Object>> loadsList(String text) {
        try {
            return MAPPER.readValue(text, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
