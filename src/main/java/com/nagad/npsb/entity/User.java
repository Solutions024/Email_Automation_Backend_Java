package com.nagad.npsb.entity;

import com.nagad.npsb.util.Py;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Someone who can sign in to the app. */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String username;  // stored in lowercase
    private String email;  // lowercase
    private String fullName = "";
    @Column(name = "`role`")
    private String role = "user";  // admin: manages users, changes partners, sends emails. user: everything else.
    private Boolean emailVerified = false;  // proven by entering the code emailed to the address
    private LocalDateTime emailVerifiedAt;
    private String pendingEmail;  // new address waiting for its confirmation code
    private String passwordHash;  // PBKDF2, never the password
    private Boolean isActive = true;
    private Integer tokenVersion = 0;  // bumped on password change / reset / sign out everywhere
    private Integer failedLoginCount = 0;
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginAt;
    private LocalDateTime passwordChangedAt;
    private LocalDateTime createdAt = Py.utcnow();
    private LocalDateTime updatedAt = Py.utcnow();

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Py.utcnow();
    }
}
