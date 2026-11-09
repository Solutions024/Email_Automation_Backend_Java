package com.nagad.npsb.web;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Uploaded files (multipart/form-data), checked like FastAPI's File(...). */
public final class Uploads {
    private Uploads() {}

    /** Largest uploaded file, and largest request body (both are read into memory). Also in application.yml. */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024, MAX_BODY_BYTES = 30L * 1024 * 1024;

    /** The uploaded file of a form field: 422 when it is missing. */
    public static MultipartFile require(String field, MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null) {
            throw new RequestValidationException(List.of(Body.error("missing", List.of("body", field), "Field required", null)));
        }
        return file;
    }

    public static ApiException fileTooLarge() {
        return new ApiException(413, "The file is too large. The limit is " + MAX_FILE_BYTES / (1024 * 1024) + " MB.");
    }

    public static ApiException requestTooLarge() {
        return new ApiException(413, "The request is too large. The limit is " + MAX_BODY_BYTES / (1024 * 1024) + " MB.");
    }
}
