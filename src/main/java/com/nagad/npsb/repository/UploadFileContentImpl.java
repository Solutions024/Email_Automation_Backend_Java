package com.nagad.npsb.repository;

import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link UploadFileContent} with plain SQL (runs in the same transaction as the JPA work around it). */
class UploadFileContentImpl implements UploadFileContent {
    private final JdbcClient jdbc;

    UploadFileContentImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public byte[] findFileContent(Integer uploadId) {
        return jdbc.sql("SELECT file_content FROM uploads WHERE id = ?")
                .param(uploadId)
                .query(byte[].class)
                .optional()
                .orElse(null);
    }

    @Override
    public void storeFileContent(Integer uploadId, byte[] content) {
        jdbc.sql("UPDATE uploads SET file_content = ? WHERE id = ?")
                .param(content)
                .param(uploadId)
                .update();
    }
}
