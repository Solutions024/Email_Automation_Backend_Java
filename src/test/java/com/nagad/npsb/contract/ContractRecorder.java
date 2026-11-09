package com.nagad.npsb.contract;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends the scenario's requests over plain HTTP (java.net.http, so every header can be read) and records each
 * one as a step: name, request, status, selected headers and the normalized body.
 *
 * Personas: a name ("admin", "maker", ...) for an access token, recorded instead of the token itself.
 * Browsers: a named cookie jar, so sign-in cookies (refresh / trusted device) are sent back like a browser does.
 */
final class ContractRecorder {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String USER_AGENT = "npsb-contract-test";
    /** Response headers that are part of the contract (Access-Control-* are added by prefix). */
    private static final List<String> HEADERS = List.of("Content-Type", "Content-Disposition", "Location", "Allow",
            "WWW-Authenticate", "Retry-After");

    private final String base;
    private final Normalizer norm;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<String, String> tokens = new HashMap<>();
    private final Map<String, Map<String, String[]>> jars = new HashMap<>();  // browser -> cookie name -> {value, path}
    private final ArrayNode steps = F.arrayNode();
    private final Set<String> names = new HashSet<>();

    ContractRecorder(String base, Normalizer norm) {
        this.base = base;
        this.norm = norm;
    }

    // ---------------------------------------------------------------- personas and browsers

    /** The access token of a persona (a missing one is kept as an invalid token: the scenario goes on, steps show 401). */
    void persona(String name, String token) {
        tokens.put(name, token == null || token.isEmpty() ? "missing-token" : token);
    }

    String token(String persona) {
        return tokens.get(persona);
    }

    /** The cookie's value in this browser, or null. */
    String cookie(String browser, String name) {
        String[] c = jars.getOrDefault(browser, Map.of()).get(name);
        return c == null ? null : c[0];
    }

    /** Put a cookie into a browser by hand (e.g. an old refresh cookie that was replaced). */
    void setCookie(String browser, String name, String value, String path) {
        jars.computeIfAbsent(browser, k -> new LinkedHashMap<>()).put(name, new String[]{value, path});
    }

    // ---------------------------------------------------------------- steps

    Call step(String name) {
        if (!names.add(name)) throw new IllegalStateException("Two steps are named \"" + name + "\"");
        return new Call(name);
    }

    /** A step that is not an HTTP call (e.g. what the mail server received), recorded as given. */
    void note(String name, JsonNode data) {
        if (!names.add(name)) throw new IllegalStateException("Two steps are named \"" + name + "\"");
        ObjectNode step = F.objectNode();
        step.put("name", name);
        step.set("observed", norm.json(data));
        steps.add(step);
    }

    /** The scenario stopped with an exception: recorded, so the comparison shows where. */
    void aborted(Throwable t) {
        ObjectNode step = F.objectNode();
        step.put("name", "scenario aborted");
        step.put("error", norm.text(String.valueOf(t)));
        steps.add(step);
    }

    ObjectNode snapshot() {
        ObjectNode root = F.objectNode();
        root.put("description", "HTTP contract of the NPSB backend, recorded by com.nagad.npsb.contract.ApiContractTest. "
                + "Record: ./gradlew contractTest -PcontractRecord=true; compare: ./gradlew contractTest");
        root.put("steps_count", steps.size());
        root.set("steps", steps.deepCopy());
        return root;
    }

    /** Two-space indented JSON with "\n" line ends on every OS (so a re-recorded snapshot is byte-identical). */
    static String pretty(JsonNode node) throws Exception {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(indenter)
                .withArrayIndenter(indenter);
        return JSON.writer(printer).writeValueAsString(node) + "\n";
    }

    /** One request: built step by step, then send() records it. */
    final class Call {
        private final String name;
        private String method = "GET", path = "/";
        private String auth;            // recorded: persona name or a label for a hand-made token
        private String authHeader;      // sent
        private String browser;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;
        private String contentType;
        private JsonNode bodyRecord;
        private String sortBy;
        private boolean ignoreBody;

        private Call(String name) {
            this.name = name;
        }

        Call get(String p) { method = "GET"; path = p; return this; }
        Call post(String p) { method = "POST"; path = p; return this; }
        Call put(String p) { method = "PUT"; path = p; return this; }
        Call patch(String p) { method = "PATCH"; path = p; return this; }
        Call delete(String p) { method = "DELETE"; path = p; return this; }
        Call options(String p) { method = "OPTIONS"; path = p; return this; }

        /** Signed in as this persona (Authorization: Bearer <its token>). */
        Call as(String persona) {
            String token = tokens.get(persona);
            if (token == null) throw new IllegalStateException("No token for persona " + persona);
            auth = persona;
            authHeader = "Bearer " + token;
            return this;
        }

        /** A hand-made Authorization header (recorded under the label, not the value). */
        Call authorization(String label, String headerValue) {
            auth = label;
            authHeader = headerValue;
            return this;
        }

        /** Send this browser's cookies (and keep the ones the answer sets). */
        Call browser(String b) {
            browser = b;
            return this;
        }

        Call header(String k, String v) {
            headers.put(k, v);
            return this;
        }

        Call json(Object value) {
            try {
                body = JSON.writeValueAsBytes(value);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            contentType = "application/json";
            bodyRecord = norm.requestJson(JSON.valueToTree(value));
            return this;
        }

        /** A body sent as is (e.g. broken JSON). */
        Call raw(String type, String text) {
            body = text.getBytes(StandardCharsets.UTF_8);
            contentType = type;
            bodyRecord = TextNode.valueOf(text);
            return this;
        }

        /** multipart/form-data with one file field. */
        Call multipart(String field, String filename, byte[] content) {
            String boundary = "----npsbcontract7f3a9c";
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            b.writeBytes(content);
            b.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            body = b.toByteArray();
            contentType = "multipart/form-data; boundary=" + boundary;
            ObjectNode rec = F.objectNode();
            rec.put("multipart_field", field);
            rec.put("filename", filename);
            bodyRecord = rec;
            return this;
        }

        /** The response is a list whose order is not defined by the API (ties): recorded sorted by this field. */
        Call sortBy(String field) {
            sortBy = field;
            return this;
        }

        /** Only status and headers are part of the contract (e.g. /docs, which springdoc will replace). */
        Call ignoreBody() {
            ignoreBody = true;
            return this;
        }

        Resp send() throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120));
            b.header("User-Agent", USER_AGENT);
            if (authHeader != null) b.header("Authorization", authHeader);
            headers.forEach(b::header);
            List<String> sentCookies = browser == null ? List.of() : cookieNames(browser, path);
            String cookieHeader = browser == null ? null : cookieHeader(browser, path);
            if (cookieHeader != null) b.header("Cookie", cookieHeader);
            if (body != null) {
                b.header("Content-Type", contentType);
                b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (browser != null) keepCookies(browser, r.headers());
            Resp resp = new Resp(r.statusCode(), r.headers(), r.body());
            steps.add(record(resp, sentCookies));
            return resp;
        }

        private ObjectNode record(Resp r, List<String> sentCookies) {
            ObjectNode step = F.objectNode();
            step.put("name", name);
            ObjectNode req = step.putObject("request");
            req.put("method", method);
            req.put("path", norm.text(path));
            if (auth != null) req.put("auth", auth);
            if (!headers.isEmpty()) {
                ObjectNode h = req.putObject("headers");
                headers.forEach((k, v) -> h.put(k, norm.text(v)));
            }
            if (!sentCookies.isEmpty()) {
                ArrayNode c = req.putArray("cookies");
                sentCookies.forEach(c::add);
            }
            if (bodyRecord != null) req.set("body", bodyRecord);

            ObjectNode res = step.putObject("response");
            res.put("status", r.status);
            ObjectNode h = F.objectNode();
            for (String n : HEADERS) {
                List<String> values = r.headers.allValues(n);
                if (values.isEmpty()) continue;
                String v = String.join(", ", values);
                if (n.equals("Content-Type")) v = v.toLowerCase(Locale.ROOT).replaceAll("\\s*;\\s*", ";");
                if (n.equals("Retry-After")) v = "<n>";
                h.put(n, norm.text(v));
            }
            List<String> cors = new ArrayList<>();
            for (String n : r.headers.map().keySet()) {
                if (n.toLowerCase(Locale.ROOT).startsWith("access-control-")) cors.add(canonical(n));
            }
            cors.sort(Comparator.naturalOrder());
            for (String n : cors) h.put(n, String.join(", ", r.headers.allValues(n)));
            List<String> cookies = r.headers.allValues("Set-Cookie");
            if (!cookies.isEmpty()) {
                ArrayNode c = h.putArray("Set-Cookie");
                for (String sc : cookies) c.add(cookieRecord(sc));
            }
            if (!h.isEmpty()) res.set("headers", h);
            JsonNode b = bodyOf(r);
            if (b != null) res.set("body", b);
            return step;
        }

        private JsonNode bodyOf(Resp r) {
            if (ignoreBody) return TextNode.valueOf("<ignored>");
            if (r.raw.length == 0) return null;
            String type = r.headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (type.contains("json")) {
                JsonNode parsed = r.json();
                if (parsed != null) {
                    JsonNode out = norm.json(parsed);
                    if (sortBy != null && out.isArray()) out = sorted((ArrayNode) out, sortBy);
                    return out;
                }
                return TextNode.valueOf(norm.text(r.text()));
            }
            if (type.startsWith("text/")) return TextNode.valueOf(norm.text(r.text()));
            return TextNode.valueOf("<binary>");
        }
    }

    private static ArrayNode sorted(ArrayNode list, String field) {
        List<JsonNode> items = new ArrayList<>();
        list.forEach(items::add);
        items.sort(Comparator.comparing((JsonNode n) -> n.path(field).isNumber() ? String.format("%020d", n.path(field).asLong())
                : n.path(field).asText()));
        ArrayNode out = F.arrayNode();
        items.forEach(out::add);
        return out;
    }

    private static String canonical(String header) {
        StringBuilder b = new StringBuilder();
        for (String part : header.toLowerCase(Locale.ROOT).split("-")) {
            if (b.length() > 0) b.append('-');
            if (!part.isEmpty()) b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    /** Set-Cookie as {name, value (masked), attributes}: the value is a secret that changes on every run. */
    private static ObjectNode cookieRecord(String setCookie) {
        String[] parts = setCookie.split(";");
        ObjectNode c = F.objectNode();
        String first = parts[0].strip();
        int eq = first.indexOf('=');
        String value = eq < 0 ? "" : first.substring(eq + 1).strip();
        c.put("name", eq < 0 ? first : first.substring(0, eq).strip());
        c.put("value", value.isEmpty() || value.equals("\"\"") ? "" : "<masked>");
        ObjectNode attrs = c.putObject("attributes");
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].strip();
            if (p.isEmpty()) continue;
            int e = p.indexOf('=');
            String key = (e < 0 ? p : p.substring(0, e)).strip().toLowerCase(Locale.ROOT);
            if (e < 0) {
                attrs.put(key, true);
            } else {
                String v = p.substring(e + 1).strip();
                if (key.equals("expires")) v = "<date>";
                if (key.equals("samesite")) v = v.toLowerCase(Locale.ROOT);
                attrs.put(key, v);
            }
        }
        return c;
    }

    private List<String> cookieNames(String browser, String path) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : jars.getOrDefault(browser, Map.of()).entrySet()) {
            if (path.startsWith(e.getValue()[1])) out.add(e.getKey());
        }
        return out;
    }

    private String cookieHeader(String browser, String path) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : jars.getOrDefault(browser, Map.of()).entrySet()) {
            if (path.startsWith(e.getValue()[1])) out.add(e.getKey() + "=" + e.getValue()[0]);
        }
        return out.isEmpty() ? null : String.join("; ", out);
    }

    private void keepCookies(String browser, HttpHeaders headers) {
        Map<String, String[]> jar = jars.computeIfAbsent(browser, k -> new LinkedHashMap<>());
        for (String sc : headers.allValues("Set-Cookie")) {
            String[] parts = sc.split(";");
            String first = parts[0].strip();
            int eq = first.indexOf('=');
            if (eq < 0) continue;
            String name = first.substring(0, eq).strip(), value = first.substring(eq + 1).strip();
            String path = "/";
            boolean gone = value.isEmpty() || value.equals("\"\"");
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].strip();
                String lower = p.toLowerCase(Locale.ROOT);
                if (lower.startsWith("path=")) path = p.substring(5);
                if (lower.equals("max-age=0")) gone = true;
            }
            if (gone) jar.remove(name);
            else jar.put(name, new String[]{value, path});
        }
    }

    /** The answer, unnormalized (for the ids and tokens the next steps need). */
    static final class Resp {
        final int status;
        final HttpHeaders headers;
        final byte[] raw;
        private JsonNode json;
        private boolean parsed;

        Resp(int status, HttpHeaders headers, byte[] raw) {
            this.status = status;
            this.headers = headers;
            this.raw = raw;
        }

        String text() {
            return new String(raw, StandardCharsets.UTF_8);
        }

        JsonNode json() {
            if (!parsed) {
                parsed = true;
                try {
                    json = raw.length == 0 ? null : JSON.readTree(raw);
                } catch (Exception e) {
                    json = null;
                }
            }
            return json;
        }

        /** The value at a JSON pointer ("/user/id"), or a missing node. */
        JsonNode at(String pointer) {
            JsonNode j = json();
            return j == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : j.at(pointer);
        }

        /** The int at a JSON pointer, or -1 (so the scenario goes on and the difference shows in the comparison). */
        int id(String pointer) {
            JsonNode n = at(pointer);
            return n.canConvertToInt() && n.isNumber() ? n.asInt() : -1;
        }

        String str(String pointer) {
            JsonNode n = at(pointer);
            return n.isTextual() ? n.asText() : null;
        }

        /** The id of the first item of the list at `pointer` whose `field` equals `value`, or -1. */
        int idWhere(String pointer, String field, String value) {
            JsonNode list = pointer.isEmpty() ? json() : at(pointer);
            if (list != null && list.isArray()) {
                for (JsonNode item : list) if (value.equals(item.path(field).asText(null))) return item.path("id").asInt(-1);
            }
            return -1;
        }
    }
}
