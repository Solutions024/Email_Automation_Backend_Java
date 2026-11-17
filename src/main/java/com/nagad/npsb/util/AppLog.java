package com.nagad.npsb.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The application log: a file on the server (APP_LOG_DIR, default backend/app/logs), never shown in the portal.
 * Logback writes it (src/main/resources/logback-spring.xml); this class adds the tagged event lines and the export.
 *
 * It holds every line the backend logs (startup, mailbox checks, warnings and errors with their stack traces)
 * and one line per recorded event, tagged so they are easy to grep:
 *   [AUTH]    sign-ins, failed sign-ins, lock-outs, codes, password changes, account changes
 *   [UPLOAD]  uploaded files
 *   [EMAIL]   an email's history: sent for approval, sent, failed, follow-ups, replies, status changes, answers
 *   [REPLY]   partner mail read from the mailbox
 *   [CHANGE]  partner / template / variable / System Settings changes (and approvals)
 *   [HTTP]    API requests that change something, and every request that failed
 * Files: app.0.log is the newest; at 10 MB it rolls over, and 20 files are kept.
 * Run this class (main) to export them to CSV and JSON in APP_LOG_DIR/export.
 */
public final class AppLog {
    private AppLog() {}

    private static final Logger LOG = LoggerFactory.getLogger("npsb");

    /** One tagged line, e.g. event("AUTH", "login_success user=ana ip=10.0.0.5"). Never throws. */
    public static void event(String tag, String text) {
        try {
            LOG.info("[{}] {}", tag, oneLine(text));
        } catch (RuntimeException ignored) {
            // logging must never break a request
        }
    }

    /** key=value pairs, skipping empty values; values with spaces are quoted. */
    public static String kv(Object... pairs) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v == null || String.valueOf(v).isEmpty()) continue;
            String s = oneLine(String.valueOf(v));
            if (s.length() > 300) s = s.substring(0, 300) + "...";
            if (b.length() > 0) b.append(' ');
            b.append(pairs[i]).append('=').append(s.contains(" ") ? "\"" + s.replace("\"", "'") + "\"" : s);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- export to CSV / JSON

    /** The start of a log line, up to the "[" before the thread: "2026-11-17 15:04:32.039 INFO    [". */
    private static final Pattern LINE_START = Pattern.compile(
            "(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}) (\\S++)\\s++\\[");
    private static final Pattern TAG = Pattern.compile("^\\[([A-Z-]+)\\] (.*)$");

    /**
     * A log line as {time, level, thread, message}, or null when it isn't one (a stack trace line). The thread ends at
     * the first "] ". Same answers as matches() with ^(time) (\S+)\s+\[(.*?)\] (.*)$, but read in one pass: like ".",
     * the thread and message can't hold a line separator, not even at the very end.
     */
    static String[] parseLine(String line) {
        Matcher m = LINE_START.matcher(line);
        if (!m.lookingAt()) return null;
        String rest = line.substring(m.end());
        for (int i = 0; i < rest.length(); i++) if (isLineSeparator(rest.charAt(i))) return null;
        int close = rest.indexOf("] ");
        if (close < 0) return null;
        return new String[]{m.group(1), m.group(2), rest.substring(0, close), rest.substring(close + 2)};
    }

    /** What "." doesn't match (without DOTALL). */
    private static boolean isLineSeparator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /**
     * The key=value pairs of a tagged line, left to right, as {key, value} (a quoted value keeps its quotes).
     * Same pairs as finding ([a-z_]+)=("[^"]*"|\S+) again and again, in one pass: a key starts where a run of
     * a-z / _ starts (starting later in the run could never match when the start didn't), and once no closing quote
     * is left, later quoted values don't search for one again.
     */
    static List<String[]> pairs(String text) {
        List<String[]> out = new ArrayList<>();
        int n = text.length(), noQuoteFrom = n, i = 0;
        while (i < n) {
            if (!isKeyChar(text.charAt(i))) {
                i++;
                continue;
            }
            int keyEnd = i;
            while (keyEnd < n && isKeyChar(text.charAt(keyEnd))) keyEnd++;
            if (keyEnd >= n || text.charAt(keyEnd) != '=') {
                i = keyEnd;
                continue;
            }
            int start = keyEnd + 1, end = -1;
            if (start < n && text.charAt(start) == '"' && start + 1 < noQuoteFrom) {  // "quoted value"
                int quote = text.indexOf('"', start + 1);
                if (quote >= 0) end = quote + 1;
                else noQuoteFrom = start + 1;
            }
            if (end < 0) {  // otherwise: up to the next whitespace
                end = start;
                while (end < n && !isSpace(text.charAt(end))) end++;
            }
            if (end == start) {  // "key=" then a space or the end: no pair here
                i = keyEnd + 1;
                continue;
            }
            out.add(new String[]{text.substring(i, keyEnd), text.substring(start, end)});
            i = end;
        }
        return out;
    }

    private static boolean isKeyChar(char c) {
        return (c >= 'a' && c <= 'z') || c == '_';
    }

    /** \s without UNICODE_CHARACTER_CLASS. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /**
     * Export the log files to a CSV and a JSON file: APP_LOG_DIR/export/app-log.csv and app-log.json
     * (replaced each time), oldest entry first. Optional argument: the log folder (default: APP_LOG_DIR or app/logs).
     */
    public static void main(String[] args) throws IOException {
        String defaultDir = System.getenv("APP_LOG_DIR") != null ? System.getenv("APP_LOG_DIR") : "app/logs";
        Path dir = Paths.get(args.length > 0 ? args[0] : defaultDir).toAbsolutePath();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var list = Files.list(dir)) {
                list.filter(p -> p.getFileName().toString().matches("app\\.\\d+\\.log")).forEach(files::add);
            }
        }
        if (files.isEmpty()) {
            System.out.println("No log files (app.N.log) in " + dir + ". Start the backend first so it writes some.");
            return;
        }
        // app.19.log is the oldest, app.0.log the newest
        files.sort(Comparator.comparingInt((Path p) -> Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""))).reversed());

        List<Map<String, String>> rows = new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parsed = parseLine(line);
                if (parsed == null) {  // a stack trace line: belongs to the entry above
                    if (!rows.isEmpty() && !line.isBlank()) {
                        Map<String, String> last = rows.get(rows.size() - 1);
                        last.merge("trace", line.strip(), (a, b) -> a + " | " + b);
                    }
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("time", parsed[0]);
                row.put("level", parsed[1]);
                row.put("thread", parsed[2]);
                String message = parsed[3];
                Matcher t = TAG.matcher(message);
                if (t.matches()) {
                    row.put("tag", t.group(1));
                    for (String[] kv : pairs(t.group(2))) {
                        String v = kv[1];
                        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                        row.put(kv[0], v);
                        keys.add(kv[0]);
                    }
                } else {
                    row.put("tag", "");
                }
                row.put("message", message);
                rows.add(row);
            }
        }

        List<String> columns = new ArrayList<>(List.of("time", "level", "tag", "thread"));
        columns.addAll(keys);
        columns.add("message");
        columns.add("trace");

        Path out = dir.resolve("export");
        Files.createDirectories(out);
        // always the same two files, replaced on every run: the export is the whole history up to now
        Path csv = out.resolve("app-log.csv"), json = out.resolve("app-log.json");

        StringBuilder c = new StringBuilder("\uFEFF");  // BOM: Excel opens it as UTF-8
        c.append(String.join(",", columns)).append("\r\n");
        for (Map<String, String> row : rows) {
            List<String> cells = new ArrayList<>();
            for (String col : columns) cells.add(csvCell(row.getOrDefault(col, "")));
            c.append(String.join(",", cells)).append("\r\n");
        }
        Files.writeString(csv, c, StandardCharsets.UTF_8);
        Files.writeString(json, PyJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rows), StandardCharsets.UTF_8);

        System.out.println(rows.size() + " log entries from " + files.size() + " file(s) in " + dir);
        System.out.println("CSV:  " + csv);
        System.out.println("JSON: " + json);
    }

    private static String csvCell(String v) {
        if (v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@")) v = "'" + v;  // no formulas in Excel
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ');
    }
}
