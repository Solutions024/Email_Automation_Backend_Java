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

/** A sign-up waiting for its emailed code (the account is created when the code is entered). */
@Table("pending_registrations")
public class PendingRegistration extends Entity {
    @Id public Integer id;
    public String username;
    public String email;
    public String fullName = "";
    public String role = "user";
    public String passwordHash;
    public String codeHash;
    public Integer attempts = 0;
    public LocalDateTime sentAt = Py.utcnow();  // last code emailed
    public Integer codesSent = 1;
    public LocalDateTime createdAt = Py.utcnow();
    public LocalDateTime expiresAt;
}
