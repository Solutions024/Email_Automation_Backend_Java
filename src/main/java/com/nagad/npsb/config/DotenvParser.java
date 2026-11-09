package com.nagad.npsb.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the .env file with python-dotenv's rules (KEY=value, quotes, # comments, ${VAR} expansion), so the
 * .env files written for the Python backend keep working unchanged.
 */
public final class DotenvParser {
    private DotenvParser() {}

    private static final Pattern LINE = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_.]*)\\s*=\\s*(.*)$");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}:]+)(?::-([^}]*))?}");

    /**
     * The .env file to read: NPSB_ENV_FILE (environment) picks one, or "none" to read no file at all (the tests do that);
     * otherwise .env is looked for from the working folder upwards, then from the backend folder upwards.
     */
    public static Path find(Path backendDir) {
        String chosen = System.getenv("NPSB_ENV_FILE");
        if (chosen != null && !chosen.isBlank()) {
            if (chosen.strip().equalsIgnoreCase("none")) return null;
            Path file = Paths.get(chosen.strip());
            if (Files.isRegularFile(file)) return file;
            System.err.println("WARNING: NPSB_ENV_FILE " + file + " does not exist; no .env file is read");
            return null;
        }
        List<Path> starts = new ArrayList<>();
        starts.add(Paths.get("").toAbsolutePath());
        if (backendDir != null) starts.add(backendDir);
        for (Path start : starts) {
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                Path candidate = dir.resolve(".env");
                if (Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    /** The values of a .env file (empty when it can't be read). */
    public static Map<String, String> read(Path file) {
        if (file == null) return Map.of();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("WARNING: could not read " + file + ": " + e.getMessage());
            return Map.of();
        }
    }

    /** The .env format of python-dotenv: KEY=value, quotes, # comments, ${VAR} expansion. */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().isEmpty() || line.strip().startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String key = m.group(1), raw = m.group(2);
            String value;
            boolean expand = true;
            if (raw.startsWith("\"") || raw.startsWith("'")) {
                char quote = raw.charAt(0);
                StringBuilder sb = new StringBuilder(raw.substring(1));
                while (sb.indexOf(String.valueOf(quote)) < 0 && i + 1 < lines.length) {  // multi-line value
                    sb.append('\n').append(lines[++i]);
                }
                String body = sb.toString();
                int end = closingQuote(body, quote);
                value = end < 0 ? body : body.substring(0, end);
                if (quote == '"') value = unescape(value);
                else expand = false;
            } else {
                int hash = raw.indexOf(" #");
                value = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            }
            if (expand) value = expand(value, out);
            out.put(key, value);
        }
        return out;
    }

    private static int closingQuote(String s, char quote) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && quote == '"') { i++; continue; }
            if (s.charAt(i) == quote) return i;
        }
        return -1;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String expand(String value, Map<String, String> sofar) {
        Matcher m = VARIABLE.matcher(value);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String found = System.getenv(name);
            if (found == null) found = sofar.get(name);
            if (found == null) found = m.group(2) == null ? "" : m.group(2);
            m.appendReplacement(b, Matcher.quoteReplacement(found));
        }
        m.appendTail(b);
        return b.toString();
    }
}
