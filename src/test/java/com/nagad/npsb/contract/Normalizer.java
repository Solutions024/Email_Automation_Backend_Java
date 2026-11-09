package com.nagad.npsb.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the values that change from run to run (clock, random tokens, ports, OS wording) with fixed
 * placeholders, so two runs of the scenario give the same snapshot. Ids are kept: every run starts on a fresh
 * database, so they are deterministic and part of the contract.
 *
 * Placeholders:
 *   <jwt>          an access token (JWT)
 *   <datetime>     an ISO-8601 date-time from the clock (2026-11-09T12:34:56, ...+00:00, ...Z)
 *   <clock-date>   today's / yesterday's / tomorrow's date (local or UTC) in the formats the app writes
 *   <time>         the time of day after a <clock-date> (export file names, quoted mails)
 *   <clock-month>  this month (yyyyMM) at the start of a case ID (CaseIds: 202611 00 001 1 000 001)
 *   <n>            a computed wait (rate limit, lock-out) in a message
 *   <hash>         the /api/activity fingerprint
 *   <smtp-port>    the port of the test's fake mail server (a free port, different on every run)
 *   <code>         a 6-digit code typed by the user (request bodies; the scenario masks it in mail subjects too)
 *
 * Also: "Connection refused: <syscall>" (JDK / OS wording) becomes "Connection refused". The recorder adds its own
 * rules for headers: Set-Cookie values are masked, Expires dates and Retry-After seconds replaced.
 */
final class Normalizer {
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern ISO_DATETIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern TIME_AFTER_DATE = Pattern.compile("<clock-date>([ _T])\\d{2}:?\\d{2}(?::\\d{2}(?:\\.\\d+)?)?");
    private static final Pattern WAIT_SECONDS = Pattern.compile("Please wait \\d+ seconds and try again");
    private static final Pattern LOCKED_MINUTES = Pattern.compile("locked for \\d+ more minute");
    private static final Pattern HASH16 = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    // "Connection refused: connect", "...: getsockopt", Windows: "...: no further information"
    private static final Pattern CONNECTION_REFUSED = Pattern.compile("Connection refused(?:: [A-Za-z]+(?: [A-Za-z]+)*)?");
    /** A case ID (CaseIds): yyyyMM + 00 + partner (3) + direction (1 / 2) + 000 + index (3). */
    private static final Pattern CASE_ID = Pattern.compile("(?<![0-9])([0-9]{6})(00[0-9]{3}[12]000[0-9]{3})(?![0-9])");

    /** Formatted clock dates (longest first, so "09 Nov 2026" is replaced before a shorter format could match). */
    private final List<String> clockDates;
    /** This month (and the next / previous one around midnight on the last / first day) as yyyyMM. */
    private final Set<String> clockMonths = new LinkedHashSet<>();
    /** Exact values (e.g. a port) -> placeholder. */
    private final Map<Long, String> literals = new LinkedHashMap<>();

    Normalizer() {
        Set<LocalDate> days = new LinkedHashSet<>();
        for (LocalDate today : List.of(LocalDate.now(), LocalDate.now(ZoneOffset.UTC))) {
            days.add(today.minusDays(1));
            days.add(today);
            days.add(today.plusDays(1));
        }
        Set<String> texts = new LinkedHashSet<>();
        for (LocalDate d : days) {
            clockMonths.add(d.format(DateTimeFormatter.ofPattern("yyyyMM", Locale.ENGLISH)));
            for (String pattern : List.of("yyyy-MM-dd", "dd MMM yyyy", "dd-MM-yyyy", "dd/MM/yyyy")) {
                texts.add(d.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)));
            }
        }
        List<String> sorted = new ArrayList<>(texts);
        sorted.sort((a, b) -> b.length() - a.length());
        clockDates = sorted;
    }

    /** Every occurrence of this number (a JSON number, or a word in a text) becomes the placeholder. */
    void literal(long value, String placeholder) {
        literals.put(value, placeholder);
    }

    /** A text with every dynamic part replaced. */
    String text(String s) {
        if (s == null) return null;
        String out = JWT.matcher(s).replaceAll("<jwt>");
        out = ISO_DATETIME.matcher(out).replaceAll("<datetime>");
        for (String d : clockDates) {
            if (out.contains(d)) out = out.replace(d, "<clock-date>");
        }
        out = TIME_AFTER_DATE.matcher(out).replaceAll("<clock-date>$1<time>");
        out = WAIT_SECONDS.matcher(out).replaceAll("Please wait <n> seconds and try again");
        out = LOCKED_MINUTES.matcher(out).replaceAll("locked for <n> more minute");
        // the same failure is worded differently by each JDK and OS ("Connection refused", "...: connect", "...: getsockopt")
        out = CONNECTION_REFUSED.matcher(out).replaceAll("Connection refused");
        out = CASE_ID.matcher(out).replaceAll(m -> Matcher.quoteReplacement(
                (clockMonths.contains(m.group(1)) ? "<clock-month>" : m.group(1)) + m.group(2)));
        for (Map.Entry<Long, String> e : literals.entrySet()) {
            out = Pattern.compile("(?<![0-9])" + e.getKey() + "(?![0-9])").matcher(out).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }

    /** A normalized deep copy of a JSON value. */
    JsonNode json(JsonNode node) {
        return json(node, null, false);
    }

    /** A request body: like json(), and the 6-digit codes the user types are masked too. */
    JsonNode requestJson(JsonNode node) {
        return json(node, null, true);
    }

    private JsonNode json(JsonNode node, String key, boolean request) {
        if (node == null) return null;
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.set(e.getKey(), json(e.getValue(), e.getKey(), request));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) out.add(json(item, null, request));
            return out;
        }
        if (node.isIntegralNumber() && literals.containsKey(node.asLong())) {
            return TextNode.valueOf(literals.get(node.asLong()));
        }
        if (node.isTextual()) {
            String v = node.asText();
            if ("stamp".equals(key) && HASH16.matcher(v).matches()) return TextNode.valueOf("<hash>");
            if (request && "code".equals(key) && SIX_DIGITS.matcher(v).matches()) return TextNode.valueOf("<code>");
            return TextNode.valueOf(text(v));
        }
        return node.deepCopy();
    }
}
