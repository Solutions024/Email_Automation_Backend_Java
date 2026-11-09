package com.nagad.npsb.repository;

/**
 * The original uploaded file (uploads.file_content, up to 25 MB). It is never loaded with the Upload row:
 * only read when a download needs it.
 */
public interface UploadFileContent {

    /** The original file, or null (none stored, or no such upload). */
    byte[] findFileContent(Integer uploadId);

    /** Store the original file of an upload that is already in the database (in the current transaction). */
    void storeFileContent(Integer uploadId, byte[] content);
}
