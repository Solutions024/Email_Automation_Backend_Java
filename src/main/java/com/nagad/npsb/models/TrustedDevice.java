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

/** A browser where the user signed in with the emailed code and ticked "Keep me signed in". */
@Table("trusted_devices")
public class TrustedDevice extends Entity {
    @Id public Integer id;
    public Integer userId;
    public String tokenHash;
    public String userAgent = "";
    public String ipAddress = "";
    public LocalDateTime createdAt = Py.utcnow();
    public LocalDateTime lastUsedAt = Py.utcnow();
    public LocalDateTime expiresAt;
    public LocalDateTime revokedAt;
}
