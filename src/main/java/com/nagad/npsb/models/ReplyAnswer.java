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

/** Our answer to a partner's message: approved by an admin, then sent in the same thread. */
@Table("reply_answers")
public class ReplyAnswer extends Entity {
    @Id public Integer id;
    public Integer dispatchId;
    public Integer replyId;
    public String toEmails;
    public String ccEmails;
    public String subject;
    public String bodyText;
    public String status = "PENDING";
    public String createdBy;
    public LocalDateTime createdAt = Py.now();
    public LocalDateTime submittedAt = Py.now();
    public String decidedBy;
    public LocalDateTime decidedAt;
    public String decisionNote;
    public LocalDateTime sendingSince;
    public LocalDateTime sentAt;
    public String messageId;
    public String error;
    public Integer attempts = 0;

    public EmailDispatch dispatch() {
        return one("dispatch", EmailDispatch.class, dispatchId);
    }

    public EmailReply reply() {
        return one("reply", EmailReply.class, replyId);
    }
}
