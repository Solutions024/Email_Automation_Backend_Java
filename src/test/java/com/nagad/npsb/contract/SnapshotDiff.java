package com.nagad.npsb.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares a recorded snapshot with a new run, step by step (matched by name). JSON is compared structurally:
 * object key order is ignored, array order is kept, numbers compare by value (20500 == 20500.0, as in JavaScript).
 * Every mismatching, missing or extra step is reported, not only the first.
 */
final class SnapshotDiff {
    private SnapshotDiff() {}

    private static final int SHOWN = 2500;  // characters of expected / actual JSON shown per step

    /** One report entry per step that differs (empty: the run matches the snapshot). */
    static List<String> compare(JsonNode expected, JsonNode actual) {
        Map<String, JsonNode> exp = byName(expected), act = byName(actual);
        List<String> report = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, JsonNode> e : exp.entrySet()) {
            index++;
            JsonNode a = act.get(e.getKey());
            if (a == null) {
                report.add(header(index, e.getValue()) + "\n  MISSING: the new run has no step with this name.\n  expected: "
                        + shown(e.getValue()));
                continue;
            }
            List<String> diffs = new ArrayList<>();
            diff("", e.getValue(), a, diffs);
            if (!diffs.isEmpty()) {
                StringBuilder b = new StringBuilder(header(index, e.getValue()));
                b.append("\n  differences:");
                for (String d : diffs) b.append("\n    ").append(d);
                b.append("\n  expected response: ").append(shown(e.getValue().has("response") ? e.getValue().get("response") : e.getValue()));
                b.append("\n  actual response:   ").append(shown(a.has("response") ? a.get("response") : a));
                report.add(b.toString());
            }
        }
        for (Map.Entry<String, JsonNode> a : act.entrySet()) {
            if (!exp.containsKey(a.getKey())) {
                report.add(header(-1, a.getValue()) + "\n  EXTRA: this step is not in the snapshot.\n  actual: " + shown(a.getValue()));
            }
        }
        return report;
    }

    private static Map<String, JsonNode> byName(JsonNode snapshot) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode step : snapshot.path("steps")) out.put(step.path("name").asText(), step);
        return out;
    }

    private static String header(int index, JsonNode step) {
        StringBuilder b = new StringBuilder();
        b.append(index > 0 ? "[step " + index + "] " : "[new step] ").append(step.path("name").asText());
        JsonNode req = step.path("request");
        if (!req.isMissingNode()) {
            b.append("\n  request: ").append(req.path("method").asText()).append(' ').append(req.path("path").asText());
            if (req.has("auth")) b.append("   (signed in as ").append(req.path("auth").asText()).append(')');
            if (req.has("headers")) b.append("\n  request headers: ").append(req.get("headers"));
            if (req.has("cookies")) b.append("\n  request cookies: ").append(req.get("cookies"));
            if (req.has("body")) b.append("\n  request body: ").append(cut(req.get("body").toString()));
        }
        return b.toString();
    }

    private static String shown(JsonNode n) {
        return cut(n == null ? "null" : n.toString());
    }

    private static String cut(String s) {
        return s.length() <= SHOWN ? s : s.substring(0, SHOWN) + " ... (" + (s.length() - SHOWN) + " more characters)";
    }

    private static String where(String path) {
        return path.isEmpty() ? "(root)" : path;
    }

    private static void diff(String path, JsonNode e, JsonNode a, List<String> out) {
        if (e.isNumber() && a.isNumber()) {
            if (new BigDecimal(e.asText()).compareTo(new BigDecimal(a.asText())) != 0) {
                out.add(where(path) + ": expected " + e + " but was " + a);
            }
            return;
        }
        if (e.isObject() && a.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            e.fieldNames().forEachRemaining(keys::add);
            Iterator<String> it = a.fieldNames();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                String p = path + "." + k;
                if (!e.has(k)) out.add(p + ": not expected, but was " + cut(a.get(k).toString()));
                else if (!a.has(k)) out.add(p + ": expected " + cut(e.get(k).toString()) + " but is missing");
                else diff(p, e.get(k), a.get(k), out);
            }
            return;
        }
        if (e.isArray() && a.isArray()) {
            int n = Math.max(e.size(), a.size());
            if (e.size() != a.size()) out.add(where(path) + ": expected " + e.size() + " items but was " + a.size());
            for (int i = 0; i < n; i++) {
                String p = path + "[" + i + "]";
                if (i >= e.size()) out.add(p + ": not expected, but was " + cut(a.get(i).toString()));
                else if (i >= a.size()) out.add(p + ": expected " + cut(e.get(i).toString()) + " but is missing");
                else diff(p, e.get(i), a.get(i), out);
            }
            return;
        }
        if (!e.equals(a)) out.add(where(path) + ": expected " + cut(e.toString()) + " but was " + cut(a.toString()));
    }
}
