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

/** A 6-digit code emailed to prove an address (purpose login / change / recovery). Only its hash is stored. */
@Table("email_verification_codes")
public class EmailVerificationCode extends Entity {
    @Id public Integer id;
    public Integer userId;
    public String email;  // the address the code was sent to
    public String purpose;
    public String codeHash;
    public Integer attempts = 0;
    public LocalDateTime createdAt = Py.utcnow();
    public LocalDateTime expiresAt;
    public LocalDateTime usedAt;  // also set when replaced

    public User user() {
        return one("user", User.class, userId);
    }
}
