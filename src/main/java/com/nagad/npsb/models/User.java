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

/** Someone who can sign in to the app. */
@Table("users")
public class User extends Entity {
    @Id public Integer id;
    public String username;  // stored in lowercase
    public String email;  // lowercase
    public String fullName = "";
    public String role = "user";  // admin: manages users, changes partners, sends emails. user: everything else.
    public Boolean emailVerified = false;  // proven by entering the code emailed to the address
    public LocalDateTime emailVerifiedAt;
    public String pendingEmail;  // new address waiting for its confirmation code
    public String passwordHash;  // PBKDF2, never the password
    public Boolean isActive = true;
    public Integer tokenVersion = 0;  // bumped on password change / reset / sign out everywhere
    public Integer failedLoginCount = 0;
    public LocalDateTime lockedUntil;
    public LocalDateTime lastLoginAt;
    public LocalDateTime passwordChangedAt;
    public LocalDateTime createdAt = Py.utcnow();
    public LocalDateTime updatedAt = Py.utcnow();

    @Override
    protected void beforeUpdate() {
        updatedAt = Py.utcnow();
    }
}
