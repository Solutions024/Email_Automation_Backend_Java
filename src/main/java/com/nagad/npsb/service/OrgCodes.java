package com.nagad.npsb.service;

/**
 * NPSB_ORG_CODE: the partner's code (e.g. 103, 207, 22002), the key partners are known by. Leading zeros don't
 * matter: "0103", "000103" and "103" are the same code, stored as "103". The upload file keeps the code as written
 * (zero padded), so the two are always compared through normalize().
 */
public final class OrgCodes {
    private OrgCodes() {}

    /** Codes are at most this many digits (after the leading zeros are dropped). */
    public static final int MAX_DIGITS = 12;

    /**
     * The code without leading zeros ("0103" -> "103", "0" or "000" -> "0"), or null when it is blank or not a
     * whole number. An Excel number cell may come as "103.0".
     */
    public static String normalize(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).strip();
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        if (s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        s = s.replaceFirst("^0+", "");
        if (s.isEmpty()) return "0";
        return s.length() > MAX_DIGITS ? null : s;
    }

    /** true when both are the same code ("0103" and "103"). */
    public static boolean same(Object a, Object b) {
        String x = normalize(a);
        return x != null && x.equals(normalize(b));
    }

    /** The code in a case ID: at least 3 digits ("7" -> "007", "103" -> "103", "22002" -> "22002"). */
    public static String forCaseId(String code) {
        String c = normalize(code);
        if (c == null) c = "0";
        return c.length() >= 3 ? c : "0".repeat(3 - c.length()) + c;
    }
}
