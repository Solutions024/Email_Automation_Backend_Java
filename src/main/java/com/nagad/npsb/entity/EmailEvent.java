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

/** History of one email: sent, follow-ups, partner messages, status changes, notes... */
@Getter
@Setter
@EntityListeners(AppLogListener.class)
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "email_events")
public class EmailEvent implements AppLogged {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer dispatchId;
    private String kind;
    private String replyStatus;
    private String note;
    private String subject;
    private String bodyHtml;
    private String toEmails;
    private String ccEmails;
    private Boolean ok = true;
    private String error;
    private String messageId;
    private Integer replyId;
    private String createdBy;
    private LocalDateTime createdAt = Py.now();

    @Override
    public void appLog(boolean inserted) {
        if (inserted) AppLog.event("EMAIL", AppLog.kv("email", dispatchId, "event", kind,
                "ok", ok, "status", replyStatus, "by", createdBy, "to", toEmails, "cc", ccEmails, "subject", subject,
                "reply", replyId, "note", note, "error", error));
    }
}
