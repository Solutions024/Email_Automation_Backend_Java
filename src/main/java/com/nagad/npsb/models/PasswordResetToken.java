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

/** A one-time "forgot password" link. Only the SHA-256 hash is stored. */
@Table("password_reset_tokens")
public class PasswordResetToken extends Entity {
    @Id public Integer id;
    public Integer userId;
    public String tokenHash;
    public LocalDateTime createdAt = Py.utcnow();
    public LocalDateTime expiresAt;
    public LocalDateTime usedAt;
    public String ipAddress = "";

    public User user() {
        return one("user", User.class, userId);
    }
}
