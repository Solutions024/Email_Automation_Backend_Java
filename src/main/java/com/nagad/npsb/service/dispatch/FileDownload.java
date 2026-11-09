package com.nagad.npsb.service.dispatch;

/** A file answered instead of JSON: its bytes, content type and the whole Content-Disposition value. */
public record FileDownload(byte[] data, String contentType, String disposition) {

    /** The .xlsx content type of the exports and attachments. */
    public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
}
