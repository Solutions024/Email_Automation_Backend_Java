package com.nagad.npsb.entity;

import com.nagad.npsb.util.AppLog;
import com.nagad.npsb.util.Py;
import jakarta.persistence.Column;
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

/** A change to partners, templates or template variables waiting for approval (maker-checker), and the change history. */
@Getter
@Setter
@EntityListeners(AppLogListener.class)
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "change_requests")
public class ChangeRequest implements AppLogged {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String entity;  // partner / template / variable / settings
    private Integer entityId;
    private String action;  // create / update / delete / default
    private String summary = "";
    @Column(name = "`before`")
    private String before;  // JSON
    @Column(name = "`after`")
    private String after;  // JSON
    private String status = "pending";  // pending / approved / rejected / cancelled / applied
    private String requestedBy;
    private LocalDateTime requestedAt = Py.now();
    private String decidedBy;
    private LocalDateTime decidedAt;
    private String note;

    @Override
    public void appLog(boolean inserted) {
        AppLog.event("CHANGE", AppLog.kv("request", id, "what", entity, "id", entityId,
                "action", action, "status", status, "by", inserted ? requestedBy : decidedBy, "summary", summary, "note", note));
    }
}
