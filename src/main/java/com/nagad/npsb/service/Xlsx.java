package com.nagad.npsb.service;

import com.nagad.npsb.util.Py;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Writes .xlsx files the way the Python backend did (pandas DataFrame.to_excel + sized columns, frozen header). */
public final class Xlsx {
    private Xlsx() {}

    /** A table -> .xlsx bytes with sized columns and a frozen header row. */
    public static byte[] write(List<String> headers, List<? extends List<?>> rows, String sheetName) {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet(Py.cut(sheetName, 31));
            CellStyle head = book.createCellStyle();
            Font bold = book.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setBorderTop(BorderStyle.THIN);
            head.setBorderBottom(BorderStyle.THIN);
            head.setBorderLeft(BorderStyle.THIN);
            head.setBorderRight(BorderStyle.THIN);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("yyyy\\-mm\\-dd"));
            CellStyle dateTimeStyle = book.createCellStyle();
            dateTimeStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("yyyy\\-mm\\-dd\\ hh:mm:ss"));

            int[] widths = new int[headers.size()];
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(head);
                widths[c] = headers.get(c).length();
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<?> values = rows.get(r);
                for (int c = 0; c < values.size() && c < headers.size(); c++) {
                    Object v = values.get(c);
                    if (v == null) continue;
                    Cell cell = row.createCell(c);
                    String shown;
                    if (v instanceof LocalDate d) {
                        cell.setCellValue(java.util.Date.from(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
                        cell.setCellStyle(dateStyle);
                        shown = Py.iso(d);
                    } else if (v instanceof LocalDateTime d) {
                        cell.setCellValue(java.util.Date.from(d.atZone(java.time.ZoneId.systemDefault()).toInstant()));
                        cell.setCellStyle(dateTimeStyle);
                        shown = Py.iso(d).replace('T', ' ');
                    } else if (v instanceof Double || v instanceof Float) {
                        double d = ((Number) v).doubleValue();
                        cell.setCellValue(d);
                        shown = Py.floatRepr(d);
                    } else if (v instanceof BigDecimal d) {
                        cell.setCellValue(d.doubleValue());
                        shown = d.toPlainString();
                    } else if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                        shown = String.valueOf(n.longValue());
                    } else if (v instanceof Boolean b) {
                        cell.setCellValue(b);
                        shown = b ? "True" : "False";
                    } else {
                        String s = String.valueOf(v);
                        cell.setCellValue(s);
                        shown = s;
                    }
                    widths[c] = Math.max(widths[c], shown.length());
                }
            }
            for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, Math.min(widths[c] + 2, 80) * 256);
            sheet.createFreezePane(0, 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
