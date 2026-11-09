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

/** A message in the mailbox that could not be read (skipped; an admin retries or dismisses it). */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "inbox_issues")
public class InboxIssue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String mailbox;
    private Long uidvalidity;
    private Long uid;
    private String remoteId;
    private String sender = "";
    private String subject = "";
    private String error;
    private Integer attempts = 1;
    private LocalDateTime createdAt = Py.now();
    private LocalDateTime resolvedAt;
    private String resolvedBy;
}
