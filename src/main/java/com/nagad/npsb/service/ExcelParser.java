package com.nagad.npsb.service;

import com.nagad.npsb.util.Py;
import com.nagad.npsb.entity.Transaction;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and checks the uploaded file (the "Final_Report_for_DMS_Claim" format).
 *
 * One row per NPSB transaction. KEY_ORG_NM is the partner (matched by name to Partner Configuration),
 * TXN_DIRECTION (Inward / Outward) picks the email template. Cells are read the way pandas read them
 * (every value as text), so the same file gives the same rows, errors and row numbers as before.
 */
public final class ExcelParser {
    private ExcelParser() {}

    /** Column key (header in lower case) -> header as written in the file */
    public static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>();
    static {
        for (String c : List.of("NPSB_TXN_RRN", "APPROVAL_DATE", "APPROVAL_DATETIME", "TXN_TYPE", "TXN_ID", "FIRST_TXN_STATUS_NAME",
                "TXN_AMOUNT", "FROMAC_NAME", "TOAC_TYPE", "ORG_TXN_STAN", "KEY_ORG_NM", "NPSB_ORG_CODE", "TXN_DIRECTION")) {
            REQUIRED_COLUMNS.put(c.toLowerCase(java.util.Locale.ROOT), c);
        }
    }
    /** These must have a value in every row; the other columns must exist but may be empty */
    private static final List<String> MUST_HAVE_VALUE = List.of("npsb_txn_rrn", "txn_amount", "key_org_nm", "npsb_org_code",
            "txn_direction");  // NPSB_ORG_CODE: the partner a row belongs to (OrgCodes)
    /** Numbers that keep their leading zeros (Excel drops them when a cell is stored as a number) */
    private static final Map<String, Integer> ZERO_PADDED = Map.of("org_txn_stan", 6, "npsb_org_code", 6);

    public static final String SHEET_NAME = "Final_Report_for_DMS_Claim";

    private static final List<List<Object>> TEMPLATE_EXAMPLE_ROWS = List.of(
            List.of("624515085655", "2026-09-02", "02-SEP-26 03.56.37.586000 PM", "NPSB - MFS to NAGAD", "00043BOS", "APPROVED",
                    20500, "bKash", "CUSTOMER", "087542", "bKash", "022002", "Inward"),
            List.of("624516087002", "2026-09-02", "02-SEP-26 04.06.11.215000 PM", "Incoming payment from Bangla QR", "00043CPG",
                    "APPROVED", 5000, "ABC Merchant", "MERCHANT", "088904", "bKash", "022002", "Inward"),
            List.of("624517090011", "2026-09-02", "02-SEP-26 05.12.40.100000 PM", "NPSB - NAGAD to MFS", "00043DQ1", "APPROVED",
                    1500, "Customer Name", "CUSTOMER", "090011", "Rocket", "022001", "Outward"));
    private static final int TEMPLATE_ROWS_WITH_DROPDOWN = 2000;  // TXN_DIRECTION dropdown on this many rows

    /** Sample .xlsx with the required headers, example rows and a TXN_DIRECTION dropdown. */
    public static byte[] buildTemplate() {
        byte[] plain = Xlsx.write(new ArrayList<>(REQUIRED_COLUMNS.values()), TEMPLATE_EXAMPLE_ROWS, SHEET_NAME);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(plain))) {
            Sheet sheet = book.getSheetAt(0);
            int column = new ArrayList<>(REQUIRED_COLUMNS.keySet()).indexOf("txn_direction");
            DataValidationHelper helper = sheet.getDataValidationHelper();
            DataValidationConstraint constraint = helper.createExplicitListConstraint(new String[]{"Inward", "Outward"});
            DataValidation dropdown = helper.createValidation(constraint,
                    new CellRangeAddressList(1, TEMPLATE_ROWS_WITH_DROPDOWN, column, column));
            dropdown.setEmptyCellAllowed(false);
            dropdown.setShowErrorBox(true);
            dropdown.createErrorBox("TXN_DIRECTION", "Choose Inward or Outward.");
            dropdown.setShowPromptBox(true);
            dropdown.createPromptBox("TXN_DIRECTION", "Inward or Outward: picks the email template.");
            sheet.addValidationData(dropdown);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Raised when the file as a whole can not be accepted. */
    public static final class FileValidationError extends Exception {
        public final List<String> errors;

        public FileValidationError(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = errors;
        }
    }

    /** A sheet as text: headers (as in the file, duplicates numbered like pandas) and rows of the same width. */
    public static final class Table {
        public final List<String> headers;
        public final List<List<String>> rows;

        Table(List<String> headers, List<List<String>> rows) {
            this.headers = headers;
            this.rows = rows;
        }
    }

    private static final Pattern HEADER_GAPS = Pattern.compile("[\\s_]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** 'NPSB_TXN_RRN ' / 'Npsb Txn Rrn' -> 'npsb_txn_rrn' */
    static String normalizeHeader(String name) {
        return HEADER_GAPS.matcher(Py.strip(name).toLowerCase(java.util.Locale.ROOT)).replaceAll("_");
    }

    /** Load the file: every cell as a string (headers as in the file). */
    public static Table readRawFile(String filename, byte[] content) throws FileValidationError {
        String name = filename.toLowerCase(java.util.Locale.ROOT);
        try {
            if (name.endsWith(".csv")) return headed(readCsv(content));
            if (name.endsWith(".xlsx")) return headed(readXlsx(content));
        } catch (FileValidationError e) {
            throw e;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new FileValidationError(List.of("Could not read the file: " + reason));
        }
        throw new FileValidationError(List.of("Only .xlsx and .csv files are allowed."));
    }

    /** First row -> headers ("Unnamed: n" for empty ones, "X.1" for a repeated one), the rest -> rows. */
    private static Table headed(List<List<String>> data) {
        if (data.isEmpty()) return new Table(new ArrayList<>(), new ArrayList<>());
        List<String> raw = data.get(0);
        List<String> headers = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();  // pandas' _dedup_names
        for (int i = 0; i < raw.size(); i++) {
            String col = raw.get(i).isEmpty() ? "Unnamed: " + i : raw.get(i);
            int cur = counts.getOrDefault(col, 0);
            while (cur > 0) {
                counts.put(col, cur + 1);
                col = col + "." + cur;
                cur = counts.getOrDefault(col, 0);
            }
            headers.add(col);
            counts.put(col, cur + 1);
        }
        return new Table(headers, new ArrayList<>(data.subList(1, data.size())));
    }

    // ---------------------------------------------------------------- .xlsx

    private static List<List<String>> readXlsx(byte[] content) throws Exception {
        List<List<String>> data = new ArrayList<>();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = book.getSheetAt(0);
            boolean date1904 = book.isDate1904();
            int last = -1;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> values = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) values.add(cellText(row.getCell(c), date1904));
                }
                while (!values.isEmpty() && values.get(values.size() - 1).isEmpty()) values.remove(values.size() - 1);
                if (!values.isEmpty()) last = r;
                data.add(values);
            }
            data = new ArrayList<>(data.subList(0, last + 1));
        }
        int width = 0;
        for (List<String> row : data) width = Math.max(width, row.size());
        for (List<String> row : data) while (row.size() < width) row.add("");
        return data;
    }

    /** A cell as pandas showed it with dtype=str. */
    private static String cellText(Cell cell, boolean date1904) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) type = cell.getCachedFormulaResultType();
        switch (type) {
            case BLANK:
                return "";
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return cell.getBooleanCellValue() ? "True" : "False";
            case ERROR:
                return "nan";
            case NUMERIC: {
                double v = cell.getNumericCellValue();
                if (DateUtil.isCellDateFormatted(cell)) {
                    if (v >= 0 && v < 1) {  // a time of day
                        java.util.Date d = DateUtil.getJavaDate(v, date1904);
                        LocalDateTime t = LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
                        String s = String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond());
                        return t.getNano() == 0 ? s : s + String.format(".%06d", t.getNano() / 1000);
                    }
                    java.util.Date d = DateUtil.getJavaDate(v, date1904);
                    LocalDateTime t = LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
                    return Py.iso(t).replace('T', ' ');
                }
                if (v == Math.rint(v) && !Double.isInfinite(v)) return new BigDecimal(v).toBigInteger().toString();
                return Py.floatRepr(v);
            }
            default:
                return "";
        }
    }

    // ---------------------------------------------------------------- .csv

    private static List<List<String>> readCsv(byte[] content) throws FileValidationError {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new FileValidationError(List.of("Could not read the file: 'utf-8' codec can't decode the file (it is not UTF-8 text)"));
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> records = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false, quotedField = false, lineHasContent = false;
        int line = 1, recordLine = 1;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : '\n';
            boolean end = i == n;
            if (inQuotes) {
                if (end) {  // file ended inside quotes
                    inQuotes = false;
                } else if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                    continue;
                } else {
                    if (c == '\n') line++;
                    field.append(c);
                    continue;
                }
            }
            if (c == '"' && field.length() == 0 && !quotedField) {
                inQuotes = true;
                quotedField = true;
                lineHasContent = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                quotedField = false;
                lineHasContent = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                record.add(field.toString());
                field.setLength(0);
                quotedField = false;
                boolean blank = !lineHasContent && record.size() == 1 && record.get(0).isBlank();
                if (!blank) {
                    records.add(record);
                    lineNumbers.add(recordLine);
                }
                record = new ArrayList<>();
                lineHasContent = false;
                if (!end) line++;
                recordLine = line;
                if (end) break;
            } else {
                field.append(c);
                if (!Character.isWhitespace(c)) lineHasContent = true;
            }
        }
        if (records.isEmpty()) throw new FileValidationError(List.of("Could not read the file: No columns to parse from file"));
        int width = records.get(0).size();
        for (int r = 1; r < records.size(); r++) {
            List<String> row = records.get(r);
            if (row.size() > width) {
                throw new FileValidationError(List.of("Could not read the file: Error tokenizing data. C error: Expected " + width
                        + " fields in line " + lineNumbers.get(r) + ", saw " + row.size() + "\n"));
            }
            while (row.size() < width) row.add("");
        }
        return records;
    }

    // ---------------------------------------------------------------- checking the rows

    private static final Pattern NUMBER_CELL = Pattern.compile("\\d+\\.0", Pattern.UNICODE_CHARACTER_CLASS);

    /** '624515085655.0' (a number cell) -> '624515085655' */
    private static String cleanNumberText(String value) {
        return NUMBER_CELL.matcher(value).matches() ? value.substring(0, value.length() - 2) : value;
    }

    private static boolean isDigits(String s) {
        return !s.isEmpty() && s.codePoints().allMatch(Character::isDigit);
    }

    private static String zfill(String s, int width) {
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < width; i++) b.append('0');
        return b.append(s).toString();
    }

    /** Decimal(text): null when it isn't a number. */
    private static BigDecimal decimal(String text) {
        String t = Py.strip(text);
        if (t.matches("[+-]?\\d+(_\\d+)*(\\.\\d*(_\\d+)*)?([eE][+-]?\\d+)?|[+-]?\\.\\d+([eE][+-]?\\d+)?")) t = t.replace("_", "");
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** total rows, valid rows (ready to save) and row errors ({"row", "rrn", "errors"}). */
    public record Parsed(int totalRows, List<Transaction> validRows, List<Map<String, Object>> rowErrors) {}

    public static Parsed parseTransactions(String filename, byte[] content) throws FileValidationError {
        Table table = readRawFile(filename, content);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < table.headers.size(); i++) index.putIfAbsent(normalizeHeader(table.headers.get(i)), i);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : REQUIRED_COLUMNS.entrySet()) if (!index.containsKey(e.getKey())) missing.add(e.getValue());
        if (!missing.isEmpty()) throw new FileValidationError(List.of("Missing required column(s): " + String.join(", ", missing)));
        if (table.rows.isEmpty()) throw new FileValidationError(List.of("The file has no transaction rows."));

        List<Transaction> valid = new ArrayList<>();
        List<Map<String, Object>> rowErrors = new ArrayList<>();
        Map<String, Integer> firstRowOfRrn = new HashMap<>();

        for (int r = 0; r < table.rows.size(); r++) {
            List<String> row = table.rows.get(r);
            int excelRow = r + 2;  // +1 for header, +1 because Excel starts at 1
            Map<String, String> values = new HashMap<>();
            for (String col : REQUIRED_COLUMNS.keySet()) values.put(col, cleanNumberText(Py.strip(row.get(index.get(col)))));
            for (Map.Entry<String, Integer> z : ZERO_PADDED.entrySet()) {
                if (isDigits(values.get(z.getKey()))) values.put(z.getKey(), zfill(values.get(z.getKey()), z.getValue()));
            }
            List<String> errors = new ArrayList<>();

            // 1) the columns every row needs
            for (String col : MUST_HAVE_VALUE) if (values.get(col).isEmpty()) errors.add(REQUIRED_COLUMNS.get(col) + " is blank");

            // 2) an RRN only once per file
            String rrn = values.get("npsb_txn_rrn");
            if (!rrn.isEmpty() && firstRowOfRrn.containsKey(rrn)) {
                errors.add("NPSB_TXN_RRN " + rrn + " is already in row " + firstRowOfRrn.get(rrn));
            } else if (!rrn.isEmpty()) {
                firstRowOfRrn.put(rrn, excelRow);
            }

            // 3) direction: Inward or Outward, it picks the email template
            String direction = values.get("txn_direction");
            String mailType = direction.isEmpty() ? null : EmailService.parseDirection(direction);
            if (!direction.isEmpty() && mailType == null) errors.add("TXN_DIRECTION '" + direction + "' must be Inward or Outward");

            // the partner's code: a whole number (leading zeros don't matter)
            String orgCode = values.get("npsb_org_code");
            if (!orgCode.isEmpty() && OrgCodes.normalize(orgCode) == null) {
                errors.add("NPSB_ORG_CODE '" + orgCode + "' is not a number");
            }

            // 4) amount must be a positive number
            BigDecimal amount = null;
            String amountText = values.get("txn_amount");
            if (!amountText.isEmpty()) {
                amount = decimal(amountText.replace(",", ""));
                if (amount == null) errors.add("TXN_AMOUNT '" + amountText + "' is not a number");
                else if (amount.signum() <= 0) errors.add("TXN_AMOUNT must be greater than 0");
            }

            // 5) when: APPROVAL_DATETIME, or at least APPROVAL_DATE
            String datetimeText = values.get("approval_datetime"), dateText = values.get("approval_date");
            LocalDateTime txnDatetime = datetimeText.isEmpty() ? null : DateParse.approvalDatetime(datetimeText);
            LocalDate approvalDate = null;
            if (!dateText.isEmpty()) {
                LocalDateTime parsed = DateParse.toDatetime(dateText, false);
                approvalDate = parsed == null ? null : parsed.toLocalDate();
                if (approvalDate == null) errors.add("APPROVAL_DATE '" + dateText + "' is not a valid date");
            }
            if (!datetimeText.isEmpty() && txnDatetime == null) {
                errors.add("APPROVAL_DATETIME '" + datetimeText + "' is not a valid date/time");
            }
            if (datetimeText.isEmpty() && dateText.isEmpty()) errors.add("APPROVAL_DATE and APPROVAL_DATETIME are both blank");
            if (txnDatetime == null && approvalDate != null) txnDatetime = approvalDate.atStartOfDay();
            if (approvalDate == null && txnDatetime != null) approvalDate = txnDatetime.toLocalDate();

            if (!errors.isEmpty()) {
                rowErrors.add(Py.map("row", excelRow, "rrn", rrn, "errors", errors));
                continue;
            }
            Transaction t = new Transaction();
            t.setExcelRow(excelRow);
            t.setRrn(rrn);
            t.setApprovalDate(approvalDate);
            t.setApprovalDatetimeText(Py.isEmpty(datetimeText) ? null : datetimeText);
            t.setTxnDatetime(txnDatetime);
            t.setTxnType(values.get("txn_type"));
            t.setTxnId(Py.isEmpty(values.get("txn_id")) ? null : values.get("txn_id"));
            t.setStatus(values.get("first_txn_status_name"));
            t.setAmount(amount);
            t.setFromAccountName(Py.isEmpty(values.get("fromac_name")) ? null : values.get("fromac_name"));
            t.setToAccountType(Py.isEmpty(values.get("toac_type")) ? null : values.get("toac_type"));
            t.setStan(values.get("org_txn_stan"));
            t.setPartnerName(values.get("key_org_nm"));
            t.setOrgCode(values.get("npsb_org_code"));
            t.setMailType(mailType);
            valid.add(t);
        }
        return new Parsed(table.rows.size(), valid, rowErrors);
    }

    /** (valid, faulty) rows of the file, with the file's own columns and values; faulty gets "Excel Row" first. */
    public static Table[] splitRows(String filename, byte[] content) throws FileValidationError {
        Parsed parsed = parseTransactions(filename, content);
        Table table = readRawFile(filename, content);
        Set<Integer> faultyIndex = new java.util.LinkedHashSet<>();
        for (Map<String, Object> e : parsed.rowErrors()) faultyIndex.add((Integer) e.get("row") - 2);  // Excel row -> row index
        List<List<String>> valid = new ArrayList<>();
        for (int i = 0; i < table.rows.size(); i++) if (!faultyIndex.contains(i)) valid.add(table.rows.get(i));
        List<String> faultyHeaders = new ArrayList<>();
        faultyHeaders.add("Excel Row");
        faultyHeaders.addAll(table.headers);
        List<List<String>> faulty = new ArrayList<>();
        for (int i : faultyIndex) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(i + 2));
            row.addAll(table.rows.get(i));
            faulty.add(row);
        }
        return new Table[]{new Table(table.headers, valid), new Table(faultyHeaders, faulty)};
    }

    /** A table -> .xlsx bytes ("Excel Row" written as a number). */
    public static byte[] toXlsx(Table table, String sheetName) {
        List<List<Object>> rows = new ArrayList<>();
        boolean numbered = !table.headers.isEmpty() && table.headers.get(0).equals("Excel Row");
        for (List<String> row : table.rows) {
            List<Object> out = new ArrayList<>(row);
            if (numbered) out.set(0, Integer.parseInt(row.get(0)));
            rows.add(out);
        }
        return Xlsx.write(table.headers, rows, sheetName);
    }

    static List<Object> list(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
