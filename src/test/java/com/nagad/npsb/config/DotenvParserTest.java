package com.nagad.npsb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DotenvParserTest {

    /** The regular expression LINE replaced, kept as the reference. */
    private static final Pattern OLD_LINE =
            Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_.]*)\\s*=\\s*(.*)$"); // NOSONAR the old regex on purpose, only given short strings

    private static String describe(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.matches() ? m.group(1) + " -> [" + m.group(2) + "]" : "no match";
    }

    private static void same(String line) {
        assertEquals(describe(OLD_LINE, line), describe(DotenvParser.LINE, line), line.replace("\r", "\\r"));
    }

    @Test
    void linesSplitAsBefore() {
        for (String line : List.of("A=1", "  A = 1 ", "export A=1", "export   A  =  x y ", "export=1", "export =1",
                "exportA=1", "export A", "A", "A =", "A=", "=1", "1A=1", "a.b_c=1", "A=\"q\" # c", "\tA\t=\t1",
                "A=\r", "A=x\r", "A=x\ry", "A= \r x", "A=x\u0085", "A=x y", "export\tB=2", "A B=1", "A=1=2")) {
            same(line);
        }
        Random random = new Random(11);
        String[] pieces = {" ", " ", "\t", "\r", "=", "=", "A", "b", "_", ".", "1", "export", "export ", "#", "\"", "x", "\u0085"};
        for (int run = 0; run < 100_000; run++) {
            StringBuilder b = new StringBuilder();
            for (int k = 1 + random.nextInt(10); k > 0; k--) b.append(pieces[random.nextInt(pieces.length)]);
            same(b.toString());
        }
    }

    @Test
    void fileParsesAsBefore() {
        Map<String, String> values = DotenvParser.parse("# comment\nexport A=1\n B = two # note\nC='x y'\nD=\"${A}-${MISSING:-z}\"\n");
        assertEquals(Map.of("A", "1", "B", "two", "C", "x y", "D", "1-z"), values);
    }

    @Test
    @Timeout(5)
    void longLinesAreFast() {
        String spaces = " ".repeat(100_000);
        for (String line : List.of("A" + spaces + "b", spaces + "1", "export" + spaces, "export" + spaces + "A" + spaces,
                "A=" + spaces + "x\ry", "A" + "\t ".repeat(50_000) + "x")) {
            assertFalse(DotenvParser.LINE.matcher(line).matches());
        }
        assertEquals("A -> [y]", describe(DotenvParser.LINE, "A=" + spaces + "\ry"));  // \r is a space here, as before
    }
}
