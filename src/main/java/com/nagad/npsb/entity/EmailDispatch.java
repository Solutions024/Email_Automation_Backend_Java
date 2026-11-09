package com.nagad.npsb.entity;

import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** The consolidated email for one partner and one mail type in one upload (never two for the same). */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "email_dispatches")
public class EmailDispatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer uploadId;
    private Integer partnerId;
    private String mailType = "outward";  // picks the template
    private String toEmails;  // comma separated
    private String ccEmails;  // comma separated
    private String subject;
    private String bodyHtml;
    private Boolean isEdited = false;  // manually edited -> kept on refresh
    private Integer templateId;
    private Integer txnCount;
    private BigDecimal totalAmount;
    private String status = "PREPARED";  // PREPARED -> PENDING -> SENDING -> SENT / FAILED
    private Integer attempts = 0;
    private String error;
    private LocalDateTime sentAt;
    private String deliveryIssues;  // JSON list of {email, reason, at}: recipients the mail server refused
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String sentBy;
    private LocalDateTime sendingSince;
    private String messageId;
    private String replyStatus = "not_received";  // not_received / received / reconciled
    private String replyStatusBy;
    private LocalDateTime replyStatusAt;
    private Integer followupCount = 0;
    private LocalDateTime lastFollowupAt;
    private String caseId;  // CaseIds: year, month, 00, partner, 1 inward / 2 outward, 000, index
    private String remarks;
    private String remarksBy;
    private LocalDateTime remarksAt;
}
