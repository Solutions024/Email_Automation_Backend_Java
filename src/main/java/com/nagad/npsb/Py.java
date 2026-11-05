package com.nagad.npsb;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small helpers that behave like the Python built-ins the original backend relied on
 * (str.split / strip / casefold, html.escape, datetime.isoformat, f"{x:,.2f}" ...), so texts,
 * dates and numbers come out exactly as before.
 */
public final class Py {
    private Py() {}

    // ---------------------------------------------------------------- strings

    /** Python's str.isspace() for one character. */
    public static boolean isSpace(int c) {
        return Character.isWhitespace(c) || c == 0x00A0 || c == 0x2007 || c == 0x202F || c == 0x0085
                || (c >= 0x1C && c <= 0x1F) || Character.getType(c) == Character.SPACE_SEPARATOR
                || c == 0x2028 || c == 0x2029;
    }

    /** str.strip() */
    public static String strip(String s) {
        if (s == null) return null;
        int start = 0, end = s.length();
        while (start < end && isSpace(s.charAt(start))) start++;
        while (end > start && isSpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /** str.strip(chars) */
    public static String strip(String s, String chars) {
        int start = 0, end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) start++;
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(start, end);
    }

    /** str.rstrip(chars) */
    public static String rstrip(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }

    /** str.split() with no arguments: split on runs of whitespace, no empty parts. */
    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && isSpace(s.charAt(i))) i++;
            int start = i;
            while (i < n && !isSpace(s.charAt(i))) i++;
            if (i > start) out.add(s.substring(start, i));
        }
        return out;
    }

    /** " ".join(s.split()) */
    public static String squash(String s) {
        return String.join(" ", split(s));
    }

    /** s.split(sep) (Python keeps empty parts). */
    public static List<String> splitOn(String s, String sep) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = s.indexOf(sep, from);
            if (at < 0) { out.add(s.substring(from)); return out; }
            out.add(s.substring(from, at));
            from = at + sep.length();
        }
    }

    /** [x for x in s.split(",") if x] */
    public static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : splitOn(s, ",")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    public static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    public static String upper(String s) { return s == null ? null : s.toUpperCase(Locale.ROOT); }

    /** str.casefold() */
    public static String casefold(String s) {
        return s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    /** s[:n] (by characters, like Python). */
    public static String cut(String s, int n) {
        if (s == null) return null;
        if (s.length() <= n) return s;
        int count = s.codePointCount(0, s.length());
        if (count <= n) return s;
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** str.capitalize() */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    public static boolean isEmpty(String s) { return s == null || s.isEmpty(); }

    /** Python truthiness of an optional string: None and "" are false. */
    public static String or(String s, String fallback) { return isEmpty(s) ? fallback : s; }

    /** html.escape(s, quote=True) */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#x27;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** list(dict.fromkeys(values)): unique, first order kept. */
    public static <T> List<T> unique(Collection<T> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    /** A LinkedHashMap from key, value pairs (keeps the order, allows nulls). */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---------------------------------------------------------------- dates

    /** datetime.now(): local time. Stored in MySQL DATETIME, so whole seconds. */
    public static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** models.utcnow(): UTC without a time zone. */
    public static LocalDateTime utcnow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    /** datetime.isoformat() */
    public static String iso(LocalDateTime d) {
        if (d == null) return null;
        String base = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", d.getYear(), d.getMonthValue(),
                d.getDayOfMonth(), d.getHour(), d.getMinute(), d.getSecond());
        int micros = d.getNano() / 1000;
        return micros == 0 ? base : base + String.format(Locale.ROOT, ".%06d", micros);
    }

    /** datetime.isoformat(timespec="seconds") */
    public static String isoSeconds(LocalDateTime d) {
        return d == null ? null : iso(d.truncatedTo(ChronoUnit.SECONDS));
    }

    /** d.replace(tzinfo=timezone.utc).isoformat() */
    public static String isoUtc(LocalDateTime d) {
        return d == null ? null : iso(d) + "+00:00";
    }

    public static String iso(LocalDate d) {
        return d == null ? null : String.format(Locale.ROOT, "%04d-%02d-%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    public static String monthAbbr(int month) { return MONTHS[month - 1]; }

    /** datetime.strftime() for the directives the app uses: %d %m %Y %y %b %H %I %M %S %f %p %%. */
    public static String strftime(LocalDateTime d, String format) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%' || i + 1 >= format.length()) { b.append(c); continue; }
            char f = format.charAt(++i);
            switch (f) {
                case 'd' -> b.append(String.format(Locale.ROOT, "%02d", d.getDayOfMonth()));
                case 'm' -> b.append(String.format(Locale.ROOT, "%02d", d.getMonthValue()));
                case 'Y' -> b.append(String.format(Locale.ROOT, "%04d", d.getYear()));
                case 'y' -> b.append(String.format(Locale.ROOT, "%02d", d.getYear() % 100));
                case 'b' -> b.append(MONTHS[d.getMonthValue() - 1]);
                case 'H' -> b.append(String.format(Locale.ROOT, "%02d", d.getHour()));
                case 'I' -> b.append(String.format(Locale.ROOT, "%02d", d.getHour() % 12 == 0 ? 12 : d.getHour() % 12));
                case 'M' -> b.append(String.format(Locale.ROOT, "%02d", d.getMinute()));
                case 'S' -> b.append(String.format(Locale.ROOT, "%02d", d.getSecond()));
                case 'f' -> b.append(String.format(Locale.ROOT, "%06d", d.getNano() / 1000));
                case 'p' -> b.append(d.getHour() < 12 ? "AM" : "PM");
                case '%' -> b.append('%');
                default -> b.append('%').append(f);
            }
        }
        return b.toString();
    }

    public static String strftime(LocalDate d, String format) {
        return strftime(d.atStartOfDay(), format);
    }

    // ---------------------------------------------------------------- numbers

    /** f"{value:,.2f}" (exact binary value, rounded half to even, like Python). */
    public static String money(double value) {
        BigDecimal exact = new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN);
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(exact);
    }

    /** repr(float) / str(float) */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        BigDecimal bd = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = bd.unscaledValue().abs().toString();
        int exponent = digits.length() - 1 - bd.scale();   // decimal exponent of the first digit
        String sign = d < 0 ? "-" : "";
        if (exponent < -4 || exponent >= 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        }
        String plain = bd.abs().toPlainString();
        if (!plain.contains(".")) plain = plain + ".0";
        return sign + plain;
    }

    /** str(Decimal) for a stored amount (scale kept, e.g. 20500.00). */
    public static String decimalStr(BigDecimal d) {
        return d == null ? "None" : d.toPlainString();
    }

    public static double toDouble(BigDecimal d) {
        return d == null ? 0.0 : d.doubleValue();
    }

    /** urllib.parse.quote(value, safe): percent-encoded UTF-8, letters, digits, "_.-~" and safe kept. */
    public static String quote(String value, String safe) {
        StringBuilder b = new StringBuilder();
        for (byte by : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = by & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "_.-~".indexOf(c) >= 0
                    || safe.indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(String.format("%02X", c));
            }
        }
        return b.toString();
    }
}
