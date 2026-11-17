package com.nagad.npsb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailCheckTest {

    /** The regular expression EmailCheck.wellFormed replaced, kept as the reference. */
    private static final Pattern OLD_EMAIL_RE = Pattern.compile(
            "(?=.{6,254}$)"
            + "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*" // NOSONAR the old regex on purpose, only given short strings
            + "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private static void same(String email) {
        assertEquals(OLD_EMAIL_RE.matcher(email).matches(), EmailCheck.wellFormed(email), email);
    }

    @Test
    void addresses() {
        for (String ok : List.of("ab@c.de", "first.last@mail.example.com", "a+tag@sub-1.example.bd", "o'neil@x.io",
                "a!#$%&'*+/=?^_`{|}~-@b.cd", "1@2.3.ab")) {
            assertTrue(EmailCheck.wellFormed(ok), ok);
            same(ok);
        }
        for (String bad : List.of("", "a@b.c", "a@b.cd ", "Ab@c.de", "a..b@c.de", ".a@c.de", "a.@c.de", "a@b@c.de",
                "a@.b.cd", "a@b..cd", "a@b.cd.", "a@-b.cd", "a@b-.cd", "a@b.c1", "a@cd", "@b.cde", "a b@c.de",
                "ab@c.de\n", "\"a\"@b.cd", "ä@b.cd", "a@b_c.de")) {
            assertFalse(EmailCheck.wellFormed(bad), bad);
            same(bad);
        }
    }

    @Test
    void lengthLimitsAsBefore() {
        String label63 = "a".repeat(63), label64 = "a".repeat(64);
        same("x@" + label63 + ".com");
        same("x@" + label64 + ".com");
        same("x@b." + "c".repeat(63));
        same("x@b." + "c".repeat(64));
        String domain = "b.".repeat(124) + "cd";              // 250 characters
        same("xy@" + domain);                                 // 253
        same("xyz@" + domain);                                // 254
        same("xyzw@" + domain);                               // 255
        same("a@b.c");                                        // 5
    }

    @Test
    void sameAnswersAsTheOldRegex() {
        Random random = new Random(7);
        String[] pieces = {"a", "b", "z", "0", "9", "-", "+", "_", "'", ".", ".", "@", "@", "com", "bd", "x1", "A", " ", "ä", "\n"};
        for (int run = 0; run < 50_000; run++) {
            StringBuilder b = new StringBuilder();
            for (int k = 1 + random.nextInt(12); k > 0; k--) b.append(pieces[random.nextInt(pieces.length)]);
            same(b.toString());
        }
    }

    @Test
    @Timeout(5)
    void longInputIsRefusedQuickly() {
        assertFalse(EmailCheck.wellFormed("a.".repeat(500_000) + "a@b.cd"));
        assertFalse(EmailCheck.wellFormed("a@" + "b.".repeat(500_000) + "cd"));
    }
}
