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

    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}) (\\S+)\\s+\\[(.*?)\\] (.*)$");
    private static final Pattern TAG = Pattern.compile("^\\[([A-Z-]+)\\] (.*)$");
    private static final Pattern PAIR = Pattern.compile("([a-z_]+)=(\"[^\"]*\"|\\S+)");

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
                Matcher m = LINE.matcher(line);
                if (!m.matches()) {  // a stack trace line: belongs to the entry above
                    if (!rows.isEmpty() && !line.isBlank()) {
                        Map<String, String> last = rows.get(rows.size() - 1);
                        last.merge("trace", line.strip(), (a, b) -> a + " | " + b);
                    }
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("time", m.group(1));
                row.put("level", m.group(2));
                row.put("thread", m.group(3));
                String message = m.group(4);
                Matcher t = TAG.matcher(message);
                if (t.matches()) {
                    row.put("tag", t.group(1));
                    Matcher kv = PAIR.matcher(t.group(2));
                    while (kv.find()) {
                        String v = kv.group(2);
                        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                        row.put(kv.group(1), v);
                        keys.add(kv.group(1));
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
