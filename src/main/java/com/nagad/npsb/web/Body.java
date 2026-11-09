package com.nagad.npsb.web;

import com.nagad.npsb.util.PyJson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A JSON request body checked like a pydantic model: each field is read with its type and default, then
 * its validators run. All problems are collected and reported together (422) by done(), in pydantic's
 * format: [{"type", "loc", "msg", "input"}], which is what the frontend expects.
 *
 * Controllers take the body as raw text, {@code @RequestBody(required = false) String body}, and read it into a typed
 * request record with this class after their sign-in checks (as the FastAPI backend did), e.g.
 * {@code PartnerRequest.from(Body.parse(body))}.
 */
public final class Body {
    private final Map<String, Object> data;
    private final List<Map<String, Object>> errors;
    private final List<Object> loc;
    private final Set<String> failed = new HashSet<>();

    private Body(Map<String, Object> data, List<Map<String, Object>> errors, List<Object> loc) {
        this.data = data;
        this.errors = errors;
        this.loc = loc;
    }

    /** The request body as sent (JSON text; null or empty when there is none), which must be a JSON object. */
    public static Body parse(String raw) {
        return of(json(raw));
    }

    /** An optional body (Model | None = None): null when the request has none. */
    public static Body parseOptional(String raw) {
        return optional(json(raw));
    }

    /** The JSON value of a request body: null when there is none (or it is JSON null); 422 when it isn't valid JSON. */
    public static Object json(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return PyJson.MAPPER.readValue(raw, Object.class);
        } catch (RuntimeException e) {
            throw new RequestValidationException(List.of(error("json_invalid", List.of("body", 0), "JSON decode error", Map.of())));
        }
    }

    /** The request body (as parsed from JSON; null when there is none), which must be a JSON object. */
    public static Body of(Object parsed) {
        if (parsed == null) {
            throw new RequestValidationException(List.of(error("missing", List.of("body"), "Field required", null)));
        }
        return from(parsed, new ArrayList<>(), List.of("body"));
    }

    /** An optional body (Model | None = None): null when the request has none. */
    public static Body optional(Object parsed) {
        if (parsed == null) return null;
        return from(parsed, new ArrayList<>(), List.of("body"));
    }

    /** A stored dict checked again (Model(**after)): problems are reported with 400. */
    public static Body ofStored(Map<String, Object> data) {
        return new Body(data == null ? new LinkedHashMap<>() : data, new ArrayList<>(), List.of());
    }

    @SuppressWarnings("unchecked")
    private static Body from(Object parsed, List<Map<String, Object>> errors, List<Object> loc) {
        if (!(parsed instanceof Map)) {
            errors.add(error("model_attributes_type", loc, "Input should be a valid dictionary or object to extract fields from", parsed));
            throw new RequestValidationException(errors);
        }
        return new Body((Map<String, Object>) parsed, errors, loc);
    }

    public static Map<String, Object> error(String type, List<Object> loc, String msg, Object input) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("loc", loc);
        e.put("msg", msg);
        e.put("input", input);
        if ("value_error".equals(type)) e.put("ctx", Map.of("error", Map.of()));
        return e;
    }

    private List<Object> at(Object... parts) {
        List<Object> out = new ArrayList<>(loc);
        out.addAll(Arrays.asList(parts));
        return out;
    }

    private void fail(String field, String type, String msg, Object input, Object... more) {
        failed.add(field);
        List<Object> where = at(field);
        where.addAll(Arrays.asList(more));
        errors.add(error(type, where, msg, input));
    }

    public boolean has(String field) {
        return data.containsKey(field);
    }

    public Map<String, Object> data() {
        return data;
    }

    // ---------------------------------------------------------------- typed fields

    private boolean missing(String field, boolean required) {
        if (data.containsKey(field)) return false;
        if (required) fail(field, "missing", "Field required", data);
        return true;
    }

    /** A str field (required when def is null and required is true). */
    public String str(String field) {
        return str(field, null, true);
    }

    public String str(String field, String def) {
        return str(field, def, false);
    }

    private String str(String field, String def, boolean required) {
        if (missing(field, required)) return def;
        Object v = data.get(field);
        if (v instanceof String s) return s;
        if (v == null && !required && def == null) return null;  // str | None
        fail(field, "string_type", "Input should be a valid string", v);
        return def;
    }

    /** str | None = None */
    public String optStr(String field) {
        if (!data.containsKey(field) || data.get(field) == null) return null;
        return str(field, null, false);
    }

    public Boolean bool(String field) {
        return bool(field, null);
    }

    public Boolean bool(String field, Boolean def) {
        if (missing(field, def == null)) return def;
        Object v = data.get(field);
        Boolean b = toBool(v);
        if (b == null) {
            if (v instanceof String || v instanceof Number) {
                fail(field, "bool_parsing", "Input should be a valid boolean, unable to interpret input", v);
            } else {
                fail(field, "bool_type", "Input should be a valid boolean", v);
            }
            return def;
        }
        return b;
    }

    /** bool | None = None */
    public Boolean optBool(String field) {
        if (!data.containsKey(field) || data.get(field) == null) return null;
        return bool(field, false);
    }

    static Boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Integer || v instanceof Long) {
            long n = ((Number) v).longValue();
            return n == 0 ? Boolean.FALSE : n == 1 ? Boolean.TRUE : null;
        }
        if (v instanceof Double d) return d == 0 ? Boolean.FALSE : d == 1 ? Boolean.TRUE : null;
        if (v instanceof String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "0", "off", "f", "false", "n", "no" -> { return false; }
                case "1", "on", "t", "true", "y", "yes" -> { return true; }
                default -> { return null; }
            }
        }
        return null;
    }

    public Integer integer(String field) {
        return integer(field, null);
    }

    public Integer integer(String field, Integer def) {
        if (missing(field, def == null)) return def;
        Object v = data.get(field);
        Object[] problem = new Object[1];
        Integer n = toInt(v, problem);
        if (n == null) {
            fail(field, (String) problem[0], intMessage((String) problem[0]), v);
            return def;
        }
        return n;
    }

    private static String intMessage(String type) {
        return switch (type) {
            case "int_parsing" -> "Input should be a valid integer, unable to parse string as an integer";
            case "int_from_float" -> "Input should be a valid integer, got a number with a fractional part";
            default -> "Input should be a valid integer";
        };
    }

    static Integer toInt(Object v, Object[] problem) {
        if (v instanceof Boolean) {
            problem[0] = "int_type";
            return null;
        }
        if (v instanceof Integer i) return i;
        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return l.intValue();
        if (v instanceof Double d) {
            if (d == Math.rint(d) && !d.isInfinite() && Math.abs(d) <= Integer.MAX_VALUE) return d.intValue();
            problem[0] = "int_from_float";
            return null;
        }
        if (v instanceof String s) {
            String t = s.strip().replace("_", "");
            try {
                return Integer.parseInt(t.startsWith("+") ? t.substring(1) : t);
            } catch (NumberFormatException e) {
                problem[0] = "int_parsing";
                return null;
            }
        }
        problem[0] = "int_type";
        return null;
    }

    /** Literal["a", "b"] (required when def is null). */
    public String literal(String field, String def, String... allowed) {
        if (missing(field, def == null)) return def;
        Object v = data.get(field);
        for (String a : allowed) if (a.equals(v)) return a;
        fail(field, "literal_error", "Input should be " + expected(allowed), v);
        return def;
    }

    /** Literal[...] | None = None */
    public String optLiteral(String field, String... allowed) {
        if (!data.containsKey(field) || data.get(field) == null) return null;
        return literal(field, allowed[0], allowed);
    }

    public static String expected(String... allowed) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) b.append(i == allowed.length - 1 ? " or " : ", ");
            b.append('\'').append(allowed[i]).append('\'');
        }
        return b.toString();
    }

    /** list[str] (required when def is null). */
    public List<String> strList(String field, List<String> def) {
        if (missing(field, def == null)) return def == null ? null : new ArrayList<>(def);
        Object v = data.get(field);
        if (!(v instanceof List<?> list)) {
            fail(field, "list_type", "Input should be a valid list", v);
            return def;
        }
        List<String> out = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof String s) out.add(s);
            else {
                fail(field, "string_type", "Input should be a valid string", list.get(i), i);
                ok = false;
            }
        }
        return ok ? out : def;
    }

    /** list[int] (required). */
    public List<Integer> intList(String field) {
        if (missing(field, true)) return null;
        Object v = data.get(field);
        if (!(v instanceof List<?> list)) {
            fail(field, "list_type", "Input should be a valid list", v);
            return null;
        }
        List<Integer> out = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < list.size(); i++) {
            Object[] problem = new Object[1];
            Integer n = toInt(list.get(i), problem);
            if (n == null) {
                fail(field, (String) problem[0], intMessage((String) problem[0]), list.get(i), i);
                ok = false;
            } else {
                out.add(n);
            }
        }
        return ok ? out : null;
    }

    /** list[Model]: one Body per item (each checked by the caller, problems collected here). */
    public List<Body> objects(String field, boolean required) {
        if (missing(field, required)) return new ArrayList<>();
        Object v = data.get(field);
        if (!(v instanceof List<?> list)) {
            fail(field, "list_type", "Input should be a valid list", v);
            return new ArrayList<>();
        }
        List<Body> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            List<Object> where = at(field, i);
            if (!(item instanceof Map<?, ?>)) {
                failed.add(field);
                errors.add(error("model_attributes_type", where, "Input should be a valid dictionary or object to extract fields from", item));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) item;
            out.add(new Body(m, errors, where));
        }
        return out;
    }

    /** dict[str, Any] */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dict(String field, Map<String, Object> def) {
        if (missing(field, def == null)) return def == null ? null : new LinkedHashMap<>(def);
        Object v = data.get(field);
        if (!(v instanceof Map<?, ?>)) {
            fail(field, "dict_type", "Input should be a valid dictionary", v);
            return def;
        }
        return new LinkedHashMap<>((Map<String, Object>) v);
    }

    // ---------------------------------------------------------------- validators

    /** @field_validator: runs when the field itself was read without problems. */
    public <T> T check(String field, T value, Function<T, T> validator) {
        if (failed.contains(field) || value == null && !data.containsKey(field)) return value;
        try {
            return validator.apply(value);
        } catch (ValueError e) {
            fail(field, "value_error", "Value error, " + e.getMessage(), data.get(field));
            return value;
        }
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    /** Raise the collected problems (422). */
    public void done() {
        if (!errors.isEmpty()) throw new RequestValidationException(errors);
    }

    /** Re-validation of stored data: problems become a 400 with a readable message. */
    public void doneOr400() {
        if (errors.isEmpty()) return;
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> e : errors) messages.add(String.valueOf(e.get("msg")).replaceFirst("^Value error, ", ""));
        throw new ApiException(400, String.join(", ", messages));
    }
}
