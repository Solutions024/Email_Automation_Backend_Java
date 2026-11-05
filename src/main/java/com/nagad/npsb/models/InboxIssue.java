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

/** A message in the mailbox that could not be read (skipped; an admin retries or dismisses it). */
@Table("inbox_issues")
public class InboxIssue extends Entity {
    @Id public Integer id;
    public String mailbox;
    public Long uidvalidity;
    public Long uid;
    public String remoteId;
    public String sender = "";
    public String subject = "";
    public String error;
    public Integer attempts = 1;
    public LocalDateTime createdAt = Py.now();
    public LocalDateTime resolvedAt;
    public String resolvedBy;
}
