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

/** A message from a partner, read from the sending mailbox (Inbox). */
@Table("email_replies")
public class EmailReply extends Entity {
    @Id public Integer id;
    public Integer dispatchId;
    public Integer partnerId;
    public String kind = "reply";
    public String messageId;
    public String fromEmail;
    public String fromName = "";
    public String toEmails;
    public String ccEmails;
    public String subject;
    public String bodyHtml;
    public String snippet = "";
    public String matchedBy;
    public LocalDateTime receivedAt;
    public LocalDateTime foundAt = Py.now();
    public Integer suggestedDispatchId;
    public String reviewReason;
    public String handledBy;
    public LocalDateTime handledAt;
    public String inReplyTo;

    public EmailDispatch dispatch() {
        return one("dispatch", EmailDispatch.class, dispatchId);
    }

    /** The attached files (without their contents: EmailReplyAttachment.content()). */
    public List<EmailReplyAttachment> attachments() {
        return many("attachments", EmailReplyAttachment.class, "WHERE t.reply_id = ? ORDER BY t.id", id);
    }

    @Override
    protected void afterCommit(boolean inserted) {
        if (inserted) com.nagad.npsb.AppLog.event("REPLY", com.nagad.npsb.AppLog.kv("reply", id, "kind", kind,
                "from", fromEmail, "email", dispatchId, "partner", partnerId, "matched_by", matchedBy, "subject", subject));
    }
}
