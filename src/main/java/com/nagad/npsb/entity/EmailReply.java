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

/** A message from a partner, read from the sending mailbox (Inbox). */
@Getter
@Setter
@EntityListeners(AppLogListener.class)
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "email_replies")
public class EmailReply implements AppLogged {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer dispatchId;
    private Integer partnerId;
    private String kind = "reply";
    private String messageId;
    private String fromEmail;
    private String fromName = "";
    private String toEmails;
    private String ccEmails;
    private String subject;
    private String bodyHtml;
    private String snippet = "";
    private String matchedBy;
    private LocalDateTime receivedAt;
    private LocalDateTime foundAt = Py.now();
    private Integer suggestedDispatchId;
    private String reviewReason;
    private String handledBy;
    private LocalDateTime handledAt;
    private String inReplyTo;

    @Override
    public void appLog(boolean inserted) {
        if (inserted) AppLog.event("REPLY", AppLog.kv("reply", id, "kind", kind,
                "from", fromEmail, "email", dispatchId, "partner", partnerId, "matched_by", matchedBy, "subject", subject));
    }
}
