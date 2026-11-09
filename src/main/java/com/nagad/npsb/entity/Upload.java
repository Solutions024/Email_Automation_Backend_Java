package com.nagad.npsb.entity;

import com.nagad.npsb.util.AppLog;
import com.nagad.npsb.util.Py;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** One uploaded dispute file. The original file (file_content) is read / written by UploadRepository, never loaded with the row. */
@Getter
@Setter
@EntityListeners(AppLogListener.class)
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "uploads")
public class Upload implements AppLogged {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String filename;
    private LocalDateTime uploadedAt = Py.now();
    private String uploadedBy;
    private Integer totalRows = 0;
    private Integer validRows = 0;

    @Override
    public void appLog(boolean inserted) {
        if (inserted) AppLog.event("UPLOAD", AppLog.kv("upload", id, "file", filename,
                "by", uploadedBy, "rows", totalRows, "valid", validRows));
    }
}
