package com.nagad.npsb.service.dispatch;

import java.util.Arrays;
import java.util.Objects;

/** A file answered instead of JSON: its bytes, content type and the whole Content-Disposition value. */
public record FileDownload(byte[] data, String contentType, String disposition) {

    /** The .xlsx content type of the exports and attachments. */
    public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // a record compares arrays by reference: these compare the file's bytes
    @Override
    public boolean equals(Object o) {
        return o instanceof FileDownload other && Arrays.equals(data, other.data) && Objects.equals(contentType, other.contentType) && Objects.equals(disposition, other.disposition);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(data) + Objects.hashCode(contentType)) + Objects.hashCode(disposition);
    }

    @Override
    public String toString() {
        return "FileDownload[data=" + (data == null ? "null" : data.length + " bytes") + ", contentType=" + contentType + ", disposition=" + disposition + "]";
    }
}
