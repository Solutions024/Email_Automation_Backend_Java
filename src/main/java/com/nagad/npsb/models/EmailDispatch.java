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

/** The consolidated email for one partner and one mail type in one upload (never two for the same). */
@Table("email_dispatches")
public class EmailDispatch extends Entity {
    @Id public Integer id;
    public Integer uploadId;
    public Integer partnerId;
    public String mailType = "outward";  // picks the template
    public String toEmails;  // comma separated
    public String ccEmails;  // comma separated
    public String subject;
    public String bodyHtml;
    public Boolean isEdited = false;  // manually edited -> kept on refresh
    public Integer templateId;
    public Integer txnCount;
    public BigDecimal totalAmount;
    public String status = "PREPARED";  // PREPARED -> PENDING -> SENDING -> SENT / FAILED
    public Integer attempts = 0;
    public String error;
    public LocalDateTime sentAt;
    public String deliveryIssues;  // JSON list of {email, reason, at}: recipients the mail server refused
    public String submittedBy;
    public LocalDateTime submittedAt;
    public String sentBy;
    public LocalDateTime sendingSince;
    public String messageId;
    public String replyStatus = "not_received";  // not_received / received / reconciled
    public String replyStatusBy;
    public LocalDateTime replyStatusAt;
    public Integer followupCount = 0;
    public LocalDateTime lastFollowupAt;
    public String caseId;  // CaseIds: year, month, 00, partner, 1 inward / 2 outward, 000, index
    public String remarks;
    public String remarksBy;
    public LocalDateTime remarksAt;

    public Partner partner() {
        return one("partner", Partner.class, partnerId);
    }

    public Upload upload() {
        return one("upload", Upload.class, uploadId);
    }
}
