package com.nagad.npsb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
}
