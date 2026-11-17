package com.nagad.npsb.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ContractRecorder.tightSemicolons: the same as the regex it replaced, in one pass. */
class ContractRecorderTest {

    private static String old(String v) {
        return v.replaceAll("\\s*;\\s*", ";"); // NOSONAR the old regex on purpose, only given short strings
    }

    @Test
    void sameAsTheOldRegex() {
        for (String v : List.of("text/html ; charset=utf-8", "application/json;charset=utf-8", "a ; ; b", " ; ", ";",
                "", "a", " a ; b ", "a\t;\nb", "a;", ";a", "a  ; b", "a ;; b", "  ")) {
            assertEquals(old(v), ContractRecorder.tightSemicolons(v), v);
        }
        Random random = new Random(13);
        String[] pieces = {";", ";", " ", " ", "\t", "\r\n", "a", "b=c", " ", " "};
        for (int run = 0; run < 100_000; run++) {
            StringBuilder b = new StringBuilder();
            for (int k = random.nextInt(12); k > 0; k--) b.append(pieces[random.nextInt(pieces.length)]);
            String v = b.toString();
            assertEquals(old(v), ContractRecorder.tightSemicolons(v), v);
        }
    }

    @Test
    @Timeout(5)
    void longWhitespaceIsFast() {
        String spaces = " ".repeat(200_000);
        assertEquals("a" + spaces + "b", ContractRecorder.tightSemicolons("a" + spaces + "b"));
        assertEquals("a;b", ContractRecorder.tightSemicolons("a" + spaces + ";" + spaces + "b"));
    }
}
