package com.nagad.npsb;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The application log: a file on the server (APP_LOG_DIR, default backend/app/logs), never shown in the portal.
 *
 * It holds every line the backend logs (startup, mailbox checks, warnings and errors with their stack traces)
 * and one line per recorded event, tagged so they are easy to grep:
 *   [AUTH]    sign-ins, failed sign-ins, lock-outs, codes, password changes, account changes (Auth.logEvent)
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

    private static final Logger LOG = Logger.getLogger("npsb");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Path directory;

    /** At startup: write everything that is logged to the log directory as well as the console. */
    public static synchronized void init() {
        if (directory != null) return;
        Path dir = Paths.get(Config.APP_LOG_DIR).toAbsolutePath();
        try {
            Files.createDirectories(dir);
            FileHandler file = new FileHandler(dir.resolve("app.%g.log").toString(), 10 * 1024 * 1024, 20, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new Line());
            file.setLevel(Level.ALL);
            Logger.getLogger("").addHandler(file);  // the root logger: our lines, the web server's errors, everything
            directory = dir;
            LOG.info("Application log: " + dir);
        } catch (IOException | RuntimeException e) {
            LOG.warning("The application log can't be written to " + dir + " (" + e.getMessage() + "): logging to the console only."
                    + " Create the folder and give the server write access, or set APP_LOG_DIR in .env.");
        }
    }

    /** One tagged line, e.g. event("AUTH", "login_success user=ana ip=10.0.0.5"). Never throws. */
    public static void event(String tag, String text) {
        try {
            LOG.info("[" + tag + "] " + oneLine(text));
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

    private static final java.util.regex.Pattern LINE = java.util.regex.Pattern.compile(
            "^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}) (\\S+)\\s+\\[(.*?)\\] (.*)$");
    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("^\\[([A-Z-]+)\\] (.*)$");
    private static final java.util.regex.Pattern PAIR = java.util.regex.Pattern.compile("([a-z_]+)=(\"[^\"]*\"|\\S+)");

    /**
     * Run this class (VS Code: "Run" above main) to export the log files to a CSV and a JSON file:
     * APP_LOG_DIR/export/app-log.csv and app-log.json (replaced each time), oldest entry first.
     * Optional argument: another log folder.
     */
    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : Config.APP_LOG_DIR).toAbsolutePath();
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
        Set<String> keys = new java.util.TreeSet<>();
        for (Path file : files) {
            for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                java.util.regex.Matcher m = LINE.matcher(line);
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
                java.util.regex.Matcher t = TAG.matcher(message);
                if (t.matches()) {
                    row.put("tag", t.group(1));
                    java.util.regex.Matcher kv = PAIR.matcher(t.group(2));
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
        Files.writeString(csv, c, java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(json, PyJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rows),
                java.nio.charset.StandardCharsets.UTF_8);

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

    /** "2026-11-08 12:00:00.123 INFO  [thread] message" (+ the stack trace of an error). */
    static final class Line extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder b = new StringBuilder();
            b.append(LocalDateTime.now().format(TIME)).append(' ')
                    .append(String.format("%-7s", r.getLevel().getName())).append(' ')
                    .append('[').append(Thread.currentThread().getName()).append("] ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter w = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(w));
                b.append(w);
            }
            return b.toString();
        }
    }
}
