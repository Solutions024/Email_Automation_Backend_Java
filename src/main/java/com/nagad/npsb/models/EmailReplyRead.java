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

/** Replies a user has opened; the others count as new. */
@Table("email_reply_reads")
public class EmailReplyRead extends Entity {
    @Id(auto = false) public Integer replyId;
    @Id(auto = false) public Integer userId;
    public LocalDateTime readAt = Py.now();
}
