package com.nagad.npsb.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AppLogTest {

    @Test
    void kvQuotesValuesWithSpacesAndSkipsEmptyOnes() {
        assertEquals("event=login user=ana detail=\"remember me\"",
                AppLog.kv("event", "login", "user", "ana", "ip", "", "note", null, "detail", "remember me"));
    }

    @Test
    void kvKeepsALogLineOnOneLine() {
        String line = AppLog.kv("subject", "first\nsecond\rthird");
        assertFalse(line.contains("\n") || line.contains("\r"), line);
    }

    @Test
    void exportWritesCsvAndJsonWithOneColumnPerField(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("app.0.log"), String.join(System.lineSeparator(),
                "2026-11-08 12:00:00.001 INFO    [main] Application log: here",
                "2026-11-08 12:00:01.002 INFO    [http-1] [AUTH] event=login_success user=admin ok=true detail=\"remember me\"",
                "2026-11-08 12:00:02.003 SEVERE  [http-2] Request failed: POST /api/uploads",
                "java.lang.IllegalStateException: boom",
                "\tat X.y(X.java:1)", ""), StandardCharsets.UTF_8);
        AppLog.main(new String[]{dir.toString()});

        String csv = Files.readString(dir.resolve("export/app-log.csv"), StandardCharsets.UTF_8);
        List<String> rows = csv.lines().toList();
        assertEquals(4, rows.size(), csv);  // header + 3 entries (the stack trace joins the error)
        assertTrue(rows.get(0).contains("event") && rows.get(0).contains("user") && rows.get(0).contains("trace"));
        assertTrue(rows.get(2).contains("login_success") && rows.get(2).contains("remember me"));
        assertTrue(rows.get(3).contains("boom"), rows.get(3));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> json = PyJson.MAPPER.readValue(dir.resolve("export/app-log.json").toFile(), List.class);
        assertEquals("AUTH", json.get(1).get("tag"));
        assertEquals("admin", json.get(1).get("user"));
    }

    @Test
    void exportNeutralisesSpreadsheetFormulas(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("app.0.log"),
                "2026-11-08 12:00:00.001 INFO    [main] [UPLOAD] file==HYPERLINK(\"http://x\") by=ana" + System.lineSeparator());
        AppLog.main(new String[]{dir.toString()});
        String csv = Files.readString(dir.resolve("export/app-log.csv"), StandardCharsets.UTF_8);
        assertFalse(csv.contains(",=HYPERLINK"), "a value starting with = must not become an Excel formula: " + csv);
    }

    // ---------------------------------------------------------------- parseLine and pairs: as the old regexes, in one pass

    /** The regular expressions parseLine and pairs replaced, kept as the reference. */
    private static final String OLD_LINE_REGEX = "^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}) (\\S+)\\s+\\[(.*?)\\] (.*)$";
    private static final Pattern OLD_LINE = Pattern.compile(OLD_LINE_REGEX); // NOSONAR the old regex on purpose, only given short strings
    private static final Pattern OLD_PAIR = Pattern.compile("([a-z_]+)=(\"[^\"]*\"|\\S+)"); // NOSONAR the old regex on purpose, only given short strings

    private static final String TIME = "2026-11-17 15:04:32.039";

    private static List<String> oldLine(String line) {
        Matcher m = OLD_LINE.matcher(line);
        return m.matches() ? List.of(m.group(1), m.group(2), m.group(3), m.group(4)) : null;
    }

    private static List<String> newLine(String line) {
        String[] p = AppLog.parseLine(line);
        return p == null ? null : List.of(p);
    }

    private static List<String> oldPairs(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = OLD_PAIR.matcher(text);
        while (m.find()) out.add(m.group(1) + "=" + m.group(2));
        return out;
    }

    private static List<String> newPairs(String text) {
        List<String> out = new ArrayList<>();
        for (String[] p : AppLog.pairs(text)) out.add(p[0] + "=" + p[1]);
        return out;
    }

    @Test
    void logLinesSplitAsBefore() {
        for (String rest : List.of("INFO    [main] Started", "WARN [http-nio-1] [AUTH] login_failed user=ana",
                "ERROR [t] ", "INFO [] x", "INFO [a] b] c", "INFO [a]] b", "INFO [a]x] b", "INFO [a b] c", "INFO [a]",
                "INFO a] b", "INFO[x [t] m", "INFO\t\t[t] m", "INFO [t] m\r", "INFO [t] m\r\n", "INFO [t] m\u2028",
                "INFO [t] m\u2028x", "INFO [t\u0085] m", "INFO [t] m\n\n", "IN\u2028FO [t] m", "INFO  [t]  m", "")) {
            String line = TIME + " " + rest;
            assertEquals(oldLine(line), newLine(line), line);
        }
        for (String other : List.of("", "\tat com.nagad.Foo.bar(Foo.java:1)", "2026-11-17 15:04:32 INFO [t] m",
                "x" + TIME + " INFO [t] m", TIME + "INFO [t] m")) {
            assertEquals(oldLine(other), newLine(other), other);
        }
        Random random = new Random(5);
        String[] pieces = {"INFO", "[", "]", "] ", " ", " ", "\t", "x", "a b", "\r", "\n", "\u2028", "\u0085", "["};
        for (int run = 0; run < 100_000; run++) {
            StringBuilder b = new StringBuilder(TIME).append(' ');
            for (int k = random.nextInt(9); k > 0; k--) b.append(pieces[random.nextInt(pieces.length)]);
            String line = b.toString();
            assertEquals(oldLine(line), newLine(line), line);
        }
    }

    @Test
    void pairsFoundAsBefore() {
        for (String text : List.of("login_success user=ana ip=10.0.0.5", "file=\"a b.xlsx\" rows=3", "a=\"x\"bc=1",
                "a=\"unclosed b=2", "a= b=1", "a=", "=1", "A=1 b=2", "ab-c=1", "x1=2", "a==b", "a=\"\"", "a=\" \" b",
                "k=v=w", "_x=1", "café=1 é=2", "a=\"x\" \"y\" b=\"z", "a=\tb", "a=\"x\ny\"", "")) {
            assertEquals(oldPairs(text), newPairs(text), text);
        }
        Random random = new Random(9);
        String[] pieces = {"a", "b", "_", "=", "=", "\"", "\"", " ", " ", "\t", "x1", "A", "é", "-", "\n"};
        for (int run = 0; run < 100_000; run++) {
            StringBuilder b = new StringBuilder();
            for (int k = random.nextInt(14); k > 0; k--) b.append(pieces[random.nextInt(pieces.length)]);
            String text = b.toString();
            assertEquals(oldPairs(text), newPairs(text), text);
        }
    }

    @Test
    @Timeout(5)
    void longLinesAreFast() {
        assertEquals(List.of(), newPairs("a".repeat(200_000)));             // the old regex rescanned this from every letter
        String quotes = "a=\"x ".repeat(40_000);                             // each quote is closed by the next one
        assertEquals(20_000, newPairs(quotes).size());
        assertEquals(oldPairs(quotes.substring(0, 2_000)), newPairs(quotes.substring(0, 2_000)));
        assertNull(AppLog.parseLine(TIME + " INFO [" + "]".repeat(200_000)));
        String spaces = " ".repeat(200_000);
        assertEquals(spaces, AppLog.parseLine(TIME + " INFO" + spaces + "[t] " + spaces)[3]);
    }
}
