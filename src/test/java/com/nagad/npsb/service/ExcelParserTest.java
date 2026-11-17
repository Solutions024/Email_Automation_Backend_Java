package com.nagad.npsb.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExcelParserTest {

    /** The regular expression withoutDigitSeparators replaced, kept as the reference. */
    private static final java.util.regex.Pattern OLD_DECIMAL =
            java.util.regex.Pattern.compile("[+-]?\\d+(_\\d+)*(\\.\\d*(_\\d+)*)?([eE][+-]?\\d+)?|[+-]?\\.\\d+([eE][+-]?\\d+)?"); // NOSONAR the old regex on purpose, only given short strings

    private static String oldWithoutDigitSeparators(String t) {
        return OLD_DECIMAL.matcher(t).matches() ? t.replace("_", "") : t;
    }

    @Test
    void digitSeparatorsAreRemovedAsBefore() {
        for (String t : List.of("1_000", "1_000.000_1", "-1_0", "+1_0e5", "1._5", "1.5_5e-3", "1_000.", "12", "1.5",
                "_1", "1_", "1__0", "1_.5", "._5", ".5_0", "1e5_0", "1e_5", "+_1", "1_a", "1_0E5", "1_0_0_0", "", "_",
                "1._", "1.__5", "١_٠", "1_000x")) {
            assertEquals(oldWithoutDigitSeparators(t), ExcelParser.withoutDigitSeparators(t), t);
        }
        java.util.Random random = new java.util.Random(3);
        String alphabet = "0129__.._eE+-x ";
        for (int run = 0; run < 100_000; run++) {
            StringBuilder b = new StringBuilder();
            for (int k = 1 + random.nextInt(12); k > 0; k--) b.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String t = b.toString();
            assertEquals(oldWithoutDigitSeparators(t), ExcelParser.withoutDigitSeparators(t), t);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void longSeparatedNumbersDoNotOverflowTheStack() {
        String t = "1" + "_1".repeat(200_000);
        assertEquals("1".repeat(200_001), ExcelParser.withoutDigitSeparators(t));
        assertEquals(t + "x", ExcelParser.withoutDigitSeparators(t + "x"));
    }

    static final String HEADER = "NPSB_TXN_RRN,APPROVAL_DATE,APPROVAL_DATETIME,TXN_TYPE,TXN_ID,FIRST_TXN_STATUS_NAME,"
            + "TXN_AMOUNT,FROMAC_NAME,TOAC_TYPE,ORG_TXN_STAN,KEY_ORG_NM,NPSB_ORG_CODE,TXN_DIRECTION\n";
    static final String ROW = ",2026-11-01,2026-11-01 10:15:00,Fund Transfer,T%s,Success,500.00,Ana,Account,123456,Test Bank,000123,%s\n";

    static String row(String rrn, String direction) {
        return rrn + String.format(ROW, rrn, direction);
    }

    @Test
    void theSampleFileIsValid() throws Exception {
        ExcelParser.Parsed p = ExcelParser.parseTransactions("sample.xlsx", ExcelParser.buildTemplate());
        assertFalse(p.validRows().isEmpty());
        assertTrue(p.rowErrors().isEmpty(), String.valueOf(p.rowErrors()));
    }

    @Test
    void anRrnTwiceInOneFileIsAFaultyRow() throws Exception {
        String csv = HEADER + row("111111111111", "Inward") + row("222222222222", "Outward") + row("111111111111", "Inward");
        ExcelParser.Parsed p = ExcelParser.parseTransactions("dups.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, p.validRows().size());
        assertEquals(1, p.rowErrors().size());
        Map<String, Object> err = p.rowErrors().get(0);
        @SuppressWarnings("unchecked") List<String> errors = (List<String>) err.get("errors");
        assertTrue(errors.get(0).contains("already in row"), String.valueOf(errors));
    }

    @Test
    void otherFileTypesAreRejected() {
        assertThrows(ExcelParser.FileValidationError.class,
                () -> ExcelParser.parseTransactions("data.txt", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMissingRequiredColumnRejectsTheFile() {
        String csv = "NPSB_TXN_RRN,TXN_AMOUNT\n1,2\n";
        assertThrows(ExcelParser.FileValidationError.class,
                () -> ExcelParser.parseTransactions("short.csv", csv.getBytes(StandardCharsets.UTF_8)));
    }
}
