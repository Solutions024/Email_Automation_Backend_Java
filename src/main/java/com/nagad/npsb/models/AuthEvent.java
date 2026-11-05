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

/** Audit log: sign-ins (successful and failed), sign-outs, password/email changes, account changes. */
@Table("auth_events")
public class AuthEvent extends Entity {
    @Id public Integer id;
    public Integer userId;
    public String username = "";  // what was typed, for failed logins
    public String event;
    public Boolean success = true;
    public String detail = "";
    public String ipAddress = "";
    public String userAgent = "";
    public LocalDateTime createdAt = Py.utcnow();
}
