package com.nagad.npsb.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** File answers (bytes with a content type) instead of JSON. */
public final class Downloads {
    private Downloads() {}

    /** A file to download; disposition is the whole Content-Disposition value (or null for none). */
    public static ResponseEntity<byte[]> file(byte[] data, String contentType, String disposition) {
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, contentType);
        if (disposition != null) b.header(HttpHeaders.CONTENT_DISPOSITION, disposition);
        return b.body(data);
    }
}
