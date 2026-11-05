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

/** History of one email: sent, follow-ups, partner messages, status changes, notes... */
@Table("email_events")
public class EmailEvent extends Entity {
    @Id public Integer id;
    public Integer dispatchId;
    public String kind;
    public String replyStatus;
    public String note;
    public String subject;
    public String bodyHtml;
    public String toEmails;
    public String ccEmails;
    public Boolean ok = true;
    public String error;
    public String messageId;
    public Integer replyId;
    public String createdBy;
    public LocalDateTime createdAt = Py.now();
}
