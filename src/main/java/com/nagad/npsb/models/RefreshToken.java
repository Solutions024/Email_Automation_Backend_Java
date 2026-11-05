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

/** One signed-in session (browser/device). Only a SHA-256 hash of the token is stored. */
@Table("refresh_tokens")
public class RefreshToken extends Entity {
    @Id public Integer id;
    public Integer userId;
    public String tokenHash;
    public String familyId;  // same for all rotations of a session
    public Boolean rememberMe = false;
    public String userAgent = "";
    public String ipAddress = "";
    public LocalDateTime createdAt = Py.utcnow();
    public LocalDateTime lastUsedAt = Py.utcnow();
    public LocalDateTime expiresAt;
    public LocalDateTime revokedAt;
    public Integer replacedById;

    public User user() {
        return one("user", User.class, userId);
    }
}
