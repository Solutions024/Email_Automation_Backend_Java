package com.nagad.npsb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateParseTest {

    /** The regular expression DateParse.tokens replaced (fine on short text, used here as the reference). */
    private static final Pattern OLD_TOKEN =
            Pattern.compile("\\d+(?::\\d+)+(?:[.,]\\d+)?|\\d+(?:\\.\\d+)?|[A-Za-z]+|[^\\sA-Za-z\\d]"); // NOSONAR the old regex on purpose, only given short strings

    private static List<String> oldTokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = OLD_TOKEN.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test
    void tokensAreTheSameAsTheOldRegex() {
        for (String s : List.of("", " ", "02-Sep-2026", "2 September 2026 3:45 PM", "12:30", "12:30:15.250", "12:30:15,5",
                "1:2:3:4.5.6", "12:", "12:30:", "12:30.", "12:30.x", "02.09.2026", "1.", ".5", "1..2", "1.2.3",
                "Sep.2,2026", "a1b2", "x:1", "\t2026/09/02\r\n", "2026 09", "é 1", "1:2,3,4", "::1", "1::2")) {
            assertEquals(oldTokens(s), DateParse.tokens(s), s);
        }
        Random random = new Random(42);
        String alphabet = "0123456789:.,-/ \tapmAPMSepx é";
        for (int run = 0; run < 20_000; run++) {
            StringBuilder b = new StringBuilder();
            for (int k = random.nextInt(25); k > 0; k--) b.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String s = b.toString();
            assertEquals(oldTokens(s), DateParse.tokens(s), s);
        }
    }

    @Test
    void datesParseAsBefore() {
        assertEquals(LocalDateTime.of(2026, 9, 2, 15, 45), DateParse.toDatetime("2 September 2026 3:45 PM", true));
        assertEquals(LocalDateTime.of(2026, 9, 2, 0, 0), DateParse.toDatetime("02.09.2026", true));
        assertEquals(LocalDateTime.of(2026, 9, 2, 12, 30, 15, 250_000_000), DateParse.toDatetime("02/09/2026 12:30:15.250", true));
        assertEquals(LocalDateTime.of(2026, 9, 2, 15, 56, 37, 586_000_000),
                DateParse.approvalDatetime("02-SEP-26 03.56.37.586000 PM"));
        assertNull(DateParse.toDatetime("not a date", true));
        assertNull(DateParse.toDatetime("02/09/2026 12:30 12:31", true));
    }

    @Test
    @Timeout(5)
    void longInputIsFastAndDoesNotOverflowTheStack() {
        String chain = "1:2".repeat(200_000);           // the old regex recursed once per ":2"
        assertEquals(1, DateParse.tokens(chain.replace("21", "2:1")).size());
        assertNull(DateParse.toDatetime(chain, true));
        String digits = "9".repeat(200_000);              // the old regex scanned this twice
        assertEquals(List.of(digits), DateParse.tokens(digits));
        assertNull(DateParse.toDatetime(digits + " x", true));
    }
}
