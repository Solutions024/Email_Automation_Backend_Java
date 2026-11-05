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

/** One recipient address of a partner. A partner can have many. */
@Table("partner_emails")
public class PartnerEmail extends Entity {
    @Id public Integer id;
    public Integer partnerId;
    public String email;
    public Boolean isActive = true;
    public String problem;  // set when the mail server refused this address: left out of new emails until fixed
    public LocalDateTime problemAt;
}
