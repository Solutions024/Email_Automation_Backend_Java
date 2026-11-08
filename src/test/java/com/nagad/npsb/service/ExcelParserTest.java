package com.nagad.npsb.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExcelParserTest {

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
