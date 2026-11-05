package com.nagad.npsb.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads dates the way the Python backend did: datetime.strptime for the Oracle export format of
 * APPROVAL_DATETIME, and pandas.to_datetime (ISO first, then dateutil's rules) for anything else.
 */
public final class DateParse {
    private DateParse() {}

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("january", 1), Map.entry("feb", 2), Map.entry("february", 2),
            Map.entry("mar", 3), Map.entry("march", 3), Map.entry("apr", 4), Map.entry("april", 4), Map.entry("may", 5),
            Map.entry("jun", 6), Map.entry("june", 6), Map.entry("jul", 7), Map.entry("july", 7), Map.entry("aug", 8),
            Map.entry("august", 8), Map.entry("sep", 9), Map.entry("sept", 9), Map.entry("september", 9),
            Map.entry("oct", 10), Map.entry("october", 10), Map.entry("nov", 11), Map.entry("november", 11),
            Map.entry("dec", 12), Map.entry("december", 12));
    private static final Map<String, Integer> ABBR = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4), Map.entry("may", 5),
            Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9), Map.entry("oct", 10),
            Map.entry("nov", 11), Map.entry("dec", 12));

    // ---------------------------------------------------------------- strptime (the Oracle export)

    private static final String D = "(3[01]|[12]\\d|0[1-9]|[1-9]| [1-9])";
    private static final String I = "(1[0-2]|0[1-9]|[1-9])";
    private static final String M = "([0-5]\\d|\\d)";
    private static final String S = "(6[0-1]|[0-5]\\d|\\d)";
    private static final Pattern[] ORACLE = {
        // %d-%b-%y %I.%M.%S.%f %p
        Pattern.compile("^" + D + "-([a-z]{3})-(\\d\\d)\\s+" + I + "\\." + M + "\\." + S + "\\.(\\d{1,6})\\s+(am|pm)$", Pattern.CASE_INSENSITIVE),
        // %d-%b-%y %I.%M.%S %p
        Pattern.compile("^" + D + "-([a-z]{3})-(\\d\\d)\\s+" + I + "\\." + M + "\\." + S + "()\\s+(am|pm)$", Pattern.CASE_INSENSITIVE),
        // %d-%b-%Y %I.%M.%S.%f %p
        Pattern.compile("^" + D + "-([a-z]{3})-(\\d\\d\\d\\d)\\s+" + I + "\\." + M + "\\." + S + "\\.(\\d{1,6})\\s+(am|pm)$", Pattern.CASE_INSENSITIVE),
    };

    /** '02-SEP-26 03.56.37.586000 PM' (Oracle export) or any normal date/time -> LocalDateTime, or null. */
    public static LocalDateTime approvalDatetime(String text) {
        text = text.strip();
        for (Pattern p : ORACLE) {
            Matcher m = p.matcher(text);
            if (!m.matches()) continue;
            Integer month = ABBR.get(m.group(2).toLowerCase(Locale.ROOT));
            if (month == null) continue;
            int year = Integer.parseInt(m.group(3));
            if (m.group(3).length() == 2) year += year <= 68 ? 2000 : 1900;
            int hour = Integer.parseInt(m.group(4)) % 12 + (m.group(8).equalsIgnoreCase("pm") ? 12 : 0);
            int micros = m.group(7).isEmpty() ? 0 : Integer.parseInt((m.group(7) + "000000").substring(0, 6));
            int second = Integer.parseInt(m.group(6));
            if (second > 59) continue;  // datetime() refuses 60 / 61
            try {
                return LocalDateTime.of(year, month, Integer.parseInt(m.group(1).strip()), hour, Integer.parseInt(m.group(5)),
                        second, micros * 1000);
            } catch (DateTimeException e) {
                // e.g. 31-FEB: try the next format
            }
        }
        return toDatetime(text, true);
    }

    // ---------------------------------------------------------------- pandas.to_datetime

    private static final Pattern ISO = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?)?\\s*(?:Z|[+-]\\d{2}(?::?\\d{2})?)?$");
    private static final Pattern COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern TOKEN = Pattern.compile("\\d+(?::\\d+)+(?:[.,]\\d+)?|\\d+(?:\\.\\d+)?|[A-Za-z]+|[^\\sA-Za-z\\d]");

    /** pandas.to_datetime(text, errors="coerce", dayfirst=...): the date/time, or null (NaT). */
    public static LocalDateTime toDatetime(String text, boolean dayfirst) {
        if (text == null) return null;
        String s = text.strip();
        if (s.isEmpty()) return null;
        try {
            Matcher iso = ISO.matcher(s);
            if (iso.matches()) {
                return LocalDateTime.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)),
                        num(iso.group(4)), num(iso.group(5)), num(iso.group(6)), nanos(iso.group(7)));
            }
            Matcher compact = COMPACT.matcher(s);
            if (compact.matches()) {
                return LocalDate.of(Integer.parseInt(compact.group(1)), Integer.parseInt(compact.group(2)),
                        Integer.parseInt(compact.group(3))).atStartOfDay();
            }
            return dateutil(s, dayfirst);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static int num(String s) {
        return s == null ? 0 : Integer.parseInt(s);
    }

    private static int nanos(String s) {
        if (s == null) return 0;
        return Integer.parseInt((s + "000000000").substring(0, 9));
    }

    /** dateutil.parser.parse for the usual shapes: numbers, month names, a time, AM/PM. */
    private static LocalDateTime dateutil(String s, boolean dayfirst) {
        List<Integer> ymd = new ArrayList<>();
        List<Boolean> longToken = new ArrayList<>();  // written with more than two digits (a year)
        int monthIndex = -1;
        int hour = 0, minute = 0, second = 0, nano = 0;
        boolean hasTime = false;
        String ampm = null;
        Matcher m = TOKEN.matcher(s);
        int consumed = 0;
        while (m.find()) {
            String gap = s.substring(consumed, m.start());
            if (!gap.isBlank()) return null;
            consumed = m.end();
            String tok = m.group();
            String low = tok.toLowerCase(Locale.ROOT);
            if (tok.contains(":")) {
                if (hasTime) return null;
                String[] parts = tok.split(":");
                hour = Integer.parseInt(parts[0]);
                minute = Integer.parseInt(parts[1]);
                if (parts.length > 2) {
                    String[] sec = parts[2].split("[.,]");
                    second = Integer.parseInt(sec[0]);
                    if (sec.length > 1) nano = nanos(sec[1]);
                }
                hasTime = true;
            } else if (Character.isDigit(tok.charAt(0))) {
                if (tok.contains(".")) {  // 02.09.2026: split into numbers
                    for (String part : tok.split("\\.")) {
                        if (part.isEmpty() || ymd.size() >= 3) return null;
                        ymd.add(Integer.parseInt(part));
                        longToken.add(part.length() > 2);
                    }
                    continue;
                }
                if (ymd.size() >= 3) return null;
                ymd.add(Integer.parseInt(tok));
                longToken.add(tok.length() > 2);
            } else if (low.equals("am") || low.equals("pm") || low.equals("a") || low.equals("p")) {
                ampm = low.substring(0, 1);
            } else if (MONTHS.containsKey(low)) {
                if (monthIndex >= 0 || ymd.size() >= 3) return null;
                monthIndex = ymd.size();
                ymd.add(MONTHS.get(low));
                longToken.add(false);
            } else if (low.equals("t") || low.equals("at") || low.equals("of") || low.equals("on")) {
                // skipped words
            } else if (tok.length() == 1 && "-/,.".contains(tok)) {
                // separators
            } else {
                return null;  // unknown word
            }
        }
        if (!s.substring(consumed).isBlank()) return null;
        if (ampm != null) {
            if (!hasTime || hour > 12) return null;
            hour = hour % 12 + (ampm.equals("p") ? 12 : 0);
        }
        int[] resolved = resolve(ymd, longToken, monthIndex, dayfirst);
        if (resolved == null) return null;
        int year = resolved[0];
        if (year < 100 && resolved[3] != 1) year = convertYear(year);
        return LocalDateTime.of(year, resolved[1], resolved[2], hour, minute, second, nano);
    }

    private static int convertYear(int year) {
        int now = LocalDate.now().getYear();
        year += now / 100 * 100;
        if (year >= now + 50) year -= 100;
        else if (year < now - 50) year += 100;
        return year;
    }

    /** {year, month, day, 1 if the year was written with 4 digits} (dateutil's _ymd.resolve_ymd). */
    private static int[] resolve(List<Integer> v, List<Boolean> longToken, int mstridx, boolean dayfirst) {
        int n = v.size();
        int ystridx = longToken.indexOf(Boolean.TRUE);
        Integer year = null, month = null, day = null;
        if (n == 3) {
            int a = v.get(0), b = v.get(1), c = v.get(2);
            if (mstridx == 0) {
                if (b > 31) { month = a; year = b; day = c; } else { month = a; day = b; year = c; }
            } else if (mstridx == 1) {
                if (a > 31) { year = a; month = b; day = c; } else { day = a; month = b; year = c; }
            } else if (mstridx == 2) {
                if (b > 31) { day = a; year = b; month = c; } else { year = a; day = b; month = c; }
            } else if (a > 31 || ystridx == 0) {
                if (dayfirst && c <= 12) { year = a; day = b; month = c; } else { year = a; month = b; day = c; }
            } else if (a > 12 || (dayfirst && b <= 12)) {
                day = a; month = b; year = c;
            } else {
                month = a; day = b; year = c;
            }
        } else if (n == 2) {
            int a = v.get(0), b = v.get(1);
            if (mstridx == 0) {
                if (b > 31) { month = a; year = b; } else { month = a; day = b; }
            } else if (mstridx == 1) {
                if (a > 31) { year = a; month = b; } else { day = a; month = b; }
            } else if (a > 31) { year = a; month = b; }
            else if (b > 31) { month = a; year = b; }
            else if (dayfirst && b <= 12) { day = a; month = b; }
            else { month = a; day = b; }
        } else if (n == 1 && mstridx == 0) {
            month = v.get(0);
        } else {
            return null;
        }
        LocalDate today = LocalDate.now();
        boolean fourDigits = year != null && ystridx >= 0 && v.indexOf(year) == ystridx;
        if (year == null) year = today.getYear();
        if (month == null) month = today.getMonthValue();
        if (day == null) day = 1;
        if (month < 1 || month > 12) return null;
        return new int[]{year, month, day, fourDigits || year >= 100 ? 1 : 0};
    }
}
