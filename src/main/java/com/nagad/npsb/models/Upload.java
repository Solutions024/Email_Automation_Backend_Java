package com.nagad.npsb.models;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Col;
import com.nagad.npsb.db.Deferred;
import com.nagad.npsb.db.Entity;
import com.nagad.npsb.db.Id;
import com.nagad.npsb.db.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** One uploaded dispute file. */
@Table("uploads")
public class Upload extends Entity {
    @Id public Integer id;
    public String filename;
    public LocalDateTime uploadedAt = Py.now();
    public String uploadedBy;
    public Integer totalRows = 0;
    public Integer validRows = 0;
    @Deferred public byte[] fileContent;  // the original file, for downloading valid / faulty rows (loaded only when asked for)

    /** The upload's valid rows, in file order. */
    public List<Transaction> transactions() {
        return many("transactions", Transaction.class, "WHERE t.upload_id = ? ORDER BY t.id", id);
    }

    /** The original file (deferred: read only when needed). */
    public byte[] content() {
        if (fileContent == null && persisted()) {
            fileContent = session().bytes("SELECT file_content FROM uploads WHERE id = ?", id);
        }
        return fileContent;
    }

    @Override
    protected void afterCommit(boolean inserted) {
        if (inserted) com.nagad.npsb.AppLog.event("UPLOAD", com.nagad.npsb.AppLog.kv("upload", id, "file", filename,
                "by", uploadedBy, "rows", totalRows, "valid", validRows));
    }
}
