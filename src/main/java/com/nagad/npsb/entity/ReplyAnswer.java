package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Our answer to a partner's message: approved by an admin, then sent in the same thread. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "reply_answers")
public class ReplyAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private Integer dispatchId;
    private Integer replyId;
    private String toEmails;
    private String ccEmails;
    private String subject;
    private String bodyText;
    private String status = "PENDING";
    private String createdBy;
    private LocalDateTime createdAt = Py.now();
    private LocalDateTime submittedAt = Py.now();
    private String decidedBy;
    private LocalDateTime decidedAt;
    private String decisionNote;
    private LocalDateTime sendingSince;
    private LocalDateTime sentAt;
    private String messageId;
    private String error;
    private Integer attempts = 0;
}
