package com.nagad.npsb.dto.auth;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ValueError;

import java.util.Locale;
import java.util.regex.Pattern;

/** The field validators of the sign-in and account request bodies (pydantic field_validators). */
public final class AccountFields {
    private AccountFields() {}

    private static final Pattern USERNAME_RE = Pattern.compile("[a-z0-9._-]{3,30}");
    private static final Pattern EMAIL_RE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+", Pattern.UNICODE_CHARACTER_CLASS);

    public static String cleanFullName(String v) {
        v = Py.squash(v);
        if (v.length() < 2 || v.length() > 100) throw new ValueError("Full name must be 2-100 characters");
        return v;
    }

    public static String cleanUsername(String v) {
        v = Py.strip(v).toLowerCase(Locale.ROOT);
        if (!USERNAME_RE.matcher(v).matches()) {
            throw new ValueError("Username must be 3-30 characters: letters, numbers, dot, dash or underscore");
        }
        return v;
    }

    public static String cleanEmail(String v) {
        v = Py.strip(v).toLowerCase(Locale.ROOT);
        if (v.length() > 254 || !EMAIL_RE.matcher(v).matches()) throw new ValueError("Enter a valid email address");
        return v;
    }

    /** A username or an email address as typed at sign-in: trimmed, lower case. */
    public static String normalizeLogin(String v) {
        return Py.strip(v).toLowerCase(Locale.ROOT);
    }
}
